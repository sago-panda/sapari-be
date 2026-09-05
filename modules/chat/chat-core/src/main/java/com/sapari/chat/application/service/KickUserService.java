package com.sapari.chat.application.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.sapari.chat.application.port.ChatKickEventPublisher;
import com.sapari.chat.command.KickUserCommand;
import com.sapari.chat.domain.exception.ChatPermissionDeniedException;
import com.sapari.chat.domain.exception.LiveNotActiveException;
import com.sapari.chat.domain.model.ChatBan;
import com.sapari.chat.domain.model.ChatBanTier;
import com.sapari.chat.domain.model.ChatKickLog;
import com.sapari.chat.domain.model.ChatMessageEvidence;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.repository.ChatBanStateRepository;
import com.sapari.chat.domain.repository.ChatBanWriteRepository;
import com.sapari.chat.domain.repository.ChatKickLogRepository;
import com.sapari.chat.domain.repository.ChatKickWriteRepository;
import com.sapari.chat.domain.repository.ChatMessageEvidenceRepository;
import com.sapari.chat.domain.rule.ChatPermissionPolicy;
import com.sapari.chat.port.KickUserUseCase;
import com.sapari.global.time.TimeProvider;
import com.sapari.live.port.GetLiveRoomUseCase;
import com.sapari.live.view.LiveRoomView;
import com.sapari.user.model.UserRole;
import com.sapari.user.view.UserView;
import com.sapari.user.port.UserAccountUseCase;

import lombok.RequiredArgsConstructor;

/**
 * 강퇴 등록 — 증거를 박제하고, 명단에 올리고, 모든 Pod에 알린다.
 *
 * <p><b>스테레오타입을 달지 않는다.</b> 이 서비스가 쓰는 저장소가 전부 블로킹(Postgres·Mongo·Redis)이라
 * 리액티브 앱(streaming-app)에는 그 의존이 존재하지 않는다. 컴포넌트 스캔에 걸리면 그 앱이 부팅에
 * 실패하므로, 이 스택을 소유한 앱이 {@code @Bean}으로 직접 등록한다.
 *
 * <p><b>트랜잭션으로 묶지 않는다.</b> 증거 로그 커밋이 확정된 뒤에 Redis와 발행이 일어나야 한다.
 * 한 트랜잭션에 넣으면 커밋 전에 Redis가 먼저 쓰이고, 롤백되면 DB에 없는 강퇴가 Redis에만 남는다.
 * 반대 순서(먼저 커밋, 실패 시 재시도)는 재시도가 안전하다 — 로그는 UNIQUE로 멱등이고 나머지 둘은
 * 같은 값을 다시 쓸 뿐이다.
 *
 * <p><b>클라이언트가 보낸 값으로 권한을 판정하지 않는다.</b> 강퇴자의 역할, 대상의 역할, 방 주인이
 * 누구인지, 방이 진행 중인지 — 넷 다 서버가 각 도메인의 포트로 직접 읽는다.
 */
@RequiredArgsConstructor
public class KickUserService implements KickUserUseCase {

    /**
     * 누적 강퇴를 세는 창. 2년이 지난 강퇴는 밴 판단에서 빠진다 — 제재는 지금의 행동에 걸어야지
     * 몇 해 전 기록으로 영구히 따라다니면 안 된다.
     */
    private static final Duration KICK_COUNT_WINDOW = Duration.ofDays(730);

    private final GetLiveRoomUseCase liveRoomReader;
    private final UserAccountUseCase userAccountReader;
    private final ChatMessageEvidenceRepository evidenceRepository;
    private final ChatKickLogRepository kickLogRepository;
    private final ChatBanStateRepository banStateRepository;
    private final ChatBanWriteRepository banWriteRepository;
    private final ChatKickWriteRepository kickWriteRepository;
    private final ChatKickEventPublisher kickEventPublisher;
    private final ChatPermissionPolicy permissionPolicy;
    private final TimeProvider timeProvider;

    /**
     * {@inheritDoc}
     *
     * <p>순서에 두 가지 의도가 있다.
     *
     * <p><b>권한 판정이 증거 조회보다 먼저다.</b> 반대로 하면 이 엔드포인트가 "그 messageId가 존재하는가"를
     * 아무에게나 알려주는 조회구가 된다 — 권한 없는 호출자도 응답 차이로 메시지 존재를 훑을 수 있다.
     *
     * <p><b>방 진행 여부 검사도 권한 뒤다.</b> 먼저 보면 권한 없는 호출자가 "이 방이 지금 켜져 있는가"를
     * 알아낼 수 있다. 없는 방과 없는 사용자를 전부 권한 거부로 접는 것도 같은 이유다 — id를 바꿔가며
     * 존재 여부를 세는 통로를 막는다.
     */
    @Override
    public void kick(KickUserCommand command) {
        LiveRoomView room = liveRoomReader.findRoom(command.roomId())
                .orElseThrow(() -> new ChatPermissionDeniedException(
                        "강퇴할 수 없는 방이다 — roomId=" + command.roomId()));

        ChatRole kickerRole = roleOf(command.kickerId());
        ChatRole targetRole = roleOf(command.targetUserId());

        if (!permissionPolicy.canKick(kickerRole, command.kickerId(), room.sellerId(),
                targetRole, command.targetUserId())) {
            throw new ChatPermissionDeniedException(
                    "강퇴 권한이 없다 — kicker=" + command.kickerId() + " room=" + command.roomId());
        }
        if (!room.live()) {
            throw new LiveNotActiveException(
                    "진행 중인 방이 아니라 강퇴할 수 없다 — roomId=" + command.roomId());
        }

        ChatMessageEvidence evidence = evidenceRepository.findEvidence(command.messageId()).orElse(null);
        ChatKickLog log = ChatKickLog.from(evidence, command.roomId(), command.targetUserId(),
                command.kickerId(), kickerRole, timeProvider.now());

        // 반환값은 "이번에 실제로 들어갔는가"다. 중복 강퇴면 false지만 여기서 되돌아가면 안 된다 —
        // 집행 캐시가 날아간 사용자를 그 방에서 다시는 강퇴하지 못하게 된다. 뒤 단계는 항상 돈다.
        // 이 값이 쓰이는 곳은 밴 승격 하나뿐이다.
        boolean firstKickInThisRoom = kickLogRepository.appendIfAbsent(log);
        syncBan(command.targetUserId(), firstKickInThisRoom, log.kickedAt());

        kickWriteRepository.register(command.roomId(), command.targetUserId());
        kickEventPublisher.publishKicked(command.roomId(), command.targetUserId());
    }

    /**
     * 밴 상태를 정본과 미러에 맞춘다 — 승격시키거나, 이미 걸린 밴을 다시 비춘다.
     *
     * <p><b>활성 밴이 있으면 새로 만들지 않고 미러만 맞춘다.</b> 그래야 재시도가 밴을 겹쳐 쌓지 않고,
     * 미러 키가 사라졌던 사용자도 다음 강퇴 때 자동으로 복구된다.
     *
     * <p><b>중복 강퇴는 카운트를 세지 않는다.</b> 같은 방 두 번째 강퇴는 정본에 행을 만들지 않으므로 누적도
     * 늘지 않는다. 여기서 굳이 다시 세면 만료된 밴을 같은 카운트로 되살릴 수 있고, 그러면 판매자가 같은
     * 사람을 반복해서 "다시 강퇴"하는 것만으로 밴을 무한히 연장하게 된다.
     *
     * <p>⚠️ 그 대가로 구멍이 하나 남는다 — 강퇴 로그는 커밋됐는데 밴 쓰기가 실패하면, 재시도는 중복
     * 경로로 들어가 승격을 건너뛴다. 반복 강퇴로 밴을 연장하는 쪽이 더 나쁘다고 보고 이 순서를 골랐다.
     */
    private void syncBan(UUID targetUserId, boolean firstKickInThisRoom, Instant now) {
        Optional<ChatBan> active = banStateRepository.findActive(targetUserId, now);
        if (active.isPresent()) {
            banWriteRepository.ban(targetUserId, active.get().expiresAt(), now);
            return;
        }
        if (!firstKickInThisRoom) {
            return;
        }
        ChatBanTier.of(kickLogRepository.countSince(targetUserId, now.minus(KICK_COUNT_WINDOW)))
                .ifPresent(tier -> {
                    ChatBan ban = ChatBan.escalated(targetUserId, tier, now);
                    // 정본 먼저다. 미러만 남으면 근거 없는 밴이 되고, 정본만 남으면 다음 강퇴가 미러를 맞춘다.
                    banStateRepository.append(ban);
                    banWriteRepository.ban(targetUserId, ban.expiresAt(), now);
                });
    }

    /**
     * 사용자의 채팅 역할. 없는 사용자는 권한 거부로 접는다 — "없는 사용자"와 "권한 없음"을 가르면
     * 강퇴 엔드포인트가 사용자 id 존재 여부를 세는 통로가 된다.
     *
     * <p><b>탈퇴 유예 상태도 그대로 통과시킨다.</b> 조회에 상태 필터가 없어서가 아니라 그게 필요해서다 —
     * 탈퇴를 신청하면 REST는 막히지만 이미 붙어 있는 채팅 세션은 끊기지 않아, 플랫폼에서 로그아웃된
     * 사람이 방에서 계속 말하는 상태가 실제로 생긴다. 강퇴가 가장 필요한 순간이다.
     */
    private ChatRole roleOf(UUID userId) {
        UserView user = userAccountReader.findById(userId)
                .orElseThrow(() -> new ChatPermissionDeniedException("알 수 없는 사용자다 — userId=" + userId));
        return toChatRole(user.role());
    }

    /**
     * 플랫폼 역할 → 채팅 역할.
     *
     * <p>{@code default}를 쓰지 않는다. 플랫폼에 역할이 하나 늘면 컴파일이 깨져서 알려주도록 세 갈래를
     * 모두 나열한다 — {@code default}로 접으면 새 역할이 조용히 구매자 취급을 받는다.
     */
    private static ChatRole toChatRole(UserRole role) {
        return switch (role) {
            case USER -> ChatRole.BUYER;
            case SELLER -> ChatRole.SELLER;
            case ADMIN -> ChatRole.ADMIN;
        };
    }
}
