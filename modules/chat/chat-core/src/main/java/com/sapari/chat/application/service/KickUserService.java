package com.sapari.chat.application.service;

import java.time.Instant;
import java.util.UUID;

import com.sapari.chat.application.port.ChatKickEventPublisher;
import com.sapari.chat.command.KickUserCommand;
import com.sapari.chat.domain.exception.ChatKickEvidenceMismatchException;
import com.sapari.chat.domain.exception.ChatPermissionDeniedException;
import com.sapari.chat.domain.exception.LiveNotActiveException;
import com.sapari.chat.domain.model.ChatKickLog;
import com.sapari.chat.domain.model.ChatMessageEvidence;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.repository.ChatBanWriteRepository;
import com.sapari.chat.domain.repository.ChatKickWriteRepository;
import com.sapari.chat.domain.repository.ChatMessageEvidenceRepository;
import com.sapari.chat.domain.rule.ChatPermissionPolicy;
import com.sapari.chat.port.KickUserUseCase;
import com.sapari.global.time.TimeProvider;
import com.sapari.live.port.GetLiveRoomUseCase;
import com.sapari.live.view.LiveRoomView;

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
 * <p><b>클라이언트가 보낸 값으로 권한을 판정하지 않는다.</b> 방 주인과 진행 여부는 live에 묻고, 강퇴자의
 * 역할은 인증된 주체에서, 대상의 역할은 증거 메시지에서 온다. 넷 중 어느 것도 요청 본문에 자리가 없다.
 *
 * <p><b>사용자 계정 저장소에는 닿지 않는다.</b> 역할을 지금 다시 물으려면 이 경로를 얹은 앱이 계정 도메인
 * 전체를 갖게 되고, 그러면 방송 앱이 계정을 고칠 수 있게 된다. 강퇴 하나가 치를 값이 아니다.
 */
@RequiredArgsConstructor
public class KickUserService implements KickUserUseCase {

    private final GetLiveRoomUseCase liveRoomReader;
    private final ChatMessageEvidenceRepository evidenceRepository;
    private final ChatKickRecorder kickRecorder;
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

        ChatRole kickerRole = kickerRole(command.kickerRole());

        // 증거를 읽기 전에 거는 <b>거친</b> 관문이다. 방 주인도 관리자도 아니면 대상이 누구든 결론이 같아서,
        // 대상의 역할을 몰라도 여기서 끝낼 수 있다. 이게 없으면 권한 없는 호출자도 증거 조회까지 도달해
        // 응답 차이로 messageId 존재를 훑는다. 최종 판정은 아래 정책이 한다 — 여기서는 정책을 흉내 내지
        // 않고, 정책이 확실히 거부할 경우만 앞당겨 끊는다.
        if (!room.sellerId().equals(command.kickerId()) && kickerRole != ChatRole.ADMIN) {
            throw new ChatPermissionDeniedException(
                    "강퇴 권한이 없다 — kicker=" + command.kickerId() + " room=" + command.roomId());
        }
        if (!room.live()) {
            throw new LiveNotActiveException(
                    "진행 중인 방이 아니라 강퇴할 수 없다 — roomId=" + command.roomId());
        }

        // 없는 증거를 여기서 끊는다. 아래에서 이 값을 다시 읽으므로, null을 흘려보내고 다른 클래스가
        // 던져 주기를 기대하면 그 계약이 바뀌는 날 여기가 조용히 NPE가 된다.
        // 예외는 불일치와 같은 것을 쓴다 — "없음"과 "안 맞음"을 가르면 messageId 탐색 오라클이 된다.
        ChatMessageEvidence evidence = evidenceRepository.findEvidence(command.messageId())
                .orElseThrow(() -> new ChatKickEvidenceMismatchException(
                        "증거 메시지가 없다 — roomId=" + command.roomId()));
        ChatKickLog log = ChatKickLog.from(evidence, command.roomId(), command.targetUserId(),
                command.kickerId(), kickerRole, timeProvider.now());

        // 대상의 역할은 증거 메시지가 들고 있다. 관리자를 끊지 못하게 하는 데에만 쓰이고, 그 값은
        // 발신 시점에 live가 서명한 룸 토큰에서 왔다.
        if (!permissionPolicy.canKick(kickerRole, command.kickerId(), room.sellerId(),
                evidence.senderRole(), command.targetUserId())) {
            throw new ChatPermissionDeniedException(
                    "강퇴 권한이 없다 — kicker=" + command.kickerId() + " room=" + command.roomId());
        }

        // DB 쓰기는 여기서 끝난다. 이 호출이 반환됐다는 건 커밋이 확정됐다는 뜻이고, 그 다음에야
        // Redis와 발행으로 간다 — 한 트랜잭션에 넣으면 롤백된 강퇴가 Redis에만 남는다.
        kickRecorder.record(log, log.kickedAt())
                .ifPresent(ban -> banWriteRepository.ban(
                        command.targetUserId(), ban.expiresAt(), log.kickedAt()));

        kickWriteRepository.register(command.roomId(), command.targetUserId());
        kickEventPublisher.publishKicked(command.roomId(), command.targetUserId());
    }

    /**
     * 인증 주체의 역할을 채팅 역할로 옮긴다.
     *
     * <p><b>이 값은 클라이언트가 보낸 것이 아니다.</b> 컨트롤러가 인증된 주체에서 채우고, 그 주체는 서명된
     * 토큰에서 나온다. 요청 본문에는 역할을 실을 자리가 없다.
     *
     * <p>모르는 이름은 거부한다. 알 수 없는 역할을 조용히 구매자로 접으면, 나중에 플랫폼에 역할이 하나
     * 늘었을 때 그 사람이 이유 없이 강퇴에 실패하는 것으로만 드러난다.
     */
    private static ChatRole kickerRole(String platformRole) {
        try {
            return toChatRole(UserRole.valueOf(platformRole));
        } catch (IllegalArgumentException e) {
            throw new ChatPermissionDeniedException("알 수 없는 역할이다 — role=" + platformRole);
        }
    }

    /**
     * 플랫폼 역할 → 채팅 역할.
     *
     * <p>{@code default}를 쓰지 않는다. 목록이 늘면 컴파일이 깨져서 알려주도록 세 갈래를 모두 나열한다 —
     * {@code default}로 접으면 새 역할이 조용히 구매자 취급을 받는다.
     */
    private static ChatRole toChatRole(UserRole role) {
        return switch (role) {
            case USER -> ChatRole.BUYER;
            case SELLER -> ChatRole.SELLER;
            case ADMIN -> ChatRole.ADMIN;
        };
    }

    /**
     * 플랫폼 역할 이름 — user 도메인의 열거와 같은 이름을 갖는다.
     *
     * <p>그쪽 타입을 직접 참조하지 않는 이유는 이 경로가 사용자 계정 도메인에 닿지 않기 위해서다.
     * 이름이 갈라지면 위 파싱이 거부로 드러난다.
     */
    private enum UserRole {
        USER, SELLER, ADMIN
    }
}
