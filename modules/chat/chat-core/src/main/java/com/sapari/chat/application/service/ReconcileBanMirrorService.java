package com.sapari.chat.application.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.sapari.chat.application.port.ChatAccountEventPublisher;
import com.sapari.chat.command.ReconcileBanMirrorCommand;
import com.sapari.chat.domain.exception.ChatPermissionDeniedException;
import com.sapari.chat.domain.model.ChatAuditAction;
import com.sapari.chat.domain.model.ChatAuditEntry;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.model.VersionedBan;
import com.sapari.chat.domain.repository.ChatBanStateRepository;
import com.sapari.chat.domain.repository.ChatBanWriteRepository;
import com.sapari.chat.domain.repository.ChatBanWriteRepository.MirrorApply;
import com.sapari.chat.domain.rule.ChatPermissionPolicy;
import com.sapari.chat.port.ReconcileBanMirrorUseCase;
import com.sapari.global.time.TimeProvider;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 밴 미러 재적재 — 강퇴 명단 재적재({@link ReconcileKickListService})의 밴 쪽 짝이다.
 *
 * <p>사용자마다 잠금 안에서 읽고 번호를 집은 뒤({@link ChatBanMirrorSnapshotter}) 커밋 밖에서 미러에
 * 쓴다. 강퇴가 미러에 쓰는 것과 같은 모양이고 같은 번호 규칙을 따르므로, 재적재 도중 들어온 해제가
 * 재적재에 지지 않는다.
 *
 * <p>번호에 지지 않은 밴마다 계정 밴 이벤트를 낸다 — 강퇴가 미러 쓰기 뒤에 내는 것과 같은 규칙이다.
 * 미러를 잃은 동안 들어온 사람의 열린 세션은 미러를 되살려도 끊기지 않는다. 그리고 강퇴와 겹치면
 * 재적재가 더 큰 번호로 먼저 미러에 닿아 강퇴 쪽 쓰기가 늦은 번호로 거절되고, 강퇴는 이벤트를 건너뛴다
 * — 그때 세션을 끊는 것은 이 이벤트뿐이다. 다시 보내도 같은 결과라 재시도해도 수렴한다.
 *
 * <p>쓰기는 늘리기 전용이라 미러에 이미 더 긴 키가 있으면 줄이지 않는다. 그 방향의 어긋남(과다 차단)은
 * 밴 해제가 고친다.
 *
 * <p>한 사용자에서 실패하면 그대로 던진다. 앞서 쓴 것은 남지만 같은 명령을 다시 보내면 같은 상태로
 * 수렴한다 — 쓰기가 정본을 비추는 것뿐이라 두 번 써도 해롭지 않다.
 *
 * <p>스테레오타입을 달지 않는다. 이 스택을 소유한 앱이 {@code @Bean}으로 등록한다.
 */
@Slf4j
@RequiredArgsConstructor
public class ReconcileBanMirrorService implements ReconcileBanMirrorUseCase {

    private final ChatPermissionPolicy permissionPolicy;
    private final ChatBanStateRepository banStateRepository;
    private final ChatBanMirrorSnapshotter snapshotter;
    private final ChatBanWriteRepository banWriteRepository;
    private final ChatAuditRecorder auditRecorder;
    private final ChatAccountEventPublisher accountEventPublisher;
    private final TimeProvider timeProvider;

    @Override
    public ReconcileOutcome reconcile(ReconcileBanMirrorCommand command) {
        ChatRole actorRole = ChatRole.fromPlatformRole(command.actorRole())
                .orElseThrow(() -> new ChatPermissionDeniedException(
                        "알 수 없는 역할이다 — role=" + command.actorRole()));
        if (!permissionPolicy.canReconcileBanMirror(actorRole)) {
            throw new ChatPermissionDeniedException(
                    "밴 미러 재적재 권한이 없다 — actor=" + command.actorId() + " role=" + actorRole);
        }

        List<UUID> userIds = banStateRepository.findActiveUserIds(timeProvider.now());
        int active = 0;
        int restored = 0;
        for (UUID userId : userIds) {
            // 시각을 사용자마다 다시 읽는다 — 남은 TTL 을 처음 시각으로 계산하면 목록이 길수록 미러가
            // 정본보다 그만큼 길어진다. 목록을 읽은 뒤 만료되거나 풀린 밴은 잠금 안에서 다시 읽어 빠진다.
            Instant now = timeProvider.now();
            Optional<VersionedBan> snapshot = snapshotter.snapshot(userId, now);
            if (snapshot.isEmpty()) {
                continue;
            }
            active++;
            VersionedBan it = snapshot.get();
            MirrorApply applied = banWriteRepository.ban(userId, it.ban().expiresAt(), now, it.version());
            // 연장은 세지 않는다. 정본과 맞는 미러에서 이 쓰기가 연장으로 나올지 무변화로 나올지는 두 지연의
            // 차가 정한다 — 강퇴가 쓴 TTL 은 커밋 전 시각(kickedAt) 기준이라 미러를 그만큼 길게 하고, 여기의
            // now 도 잠금·커밋 앞에서 읽혀 want 를 그만큼 길게 한다. 그 차는 코드로 정해지지 않아, 진짜로
            // 짧았던 미러와 ms 급 지연차가 같은 반환으로 섞인다.
            if (applied == MirrorApply.CREATED) {
                restored++;
            }
            if (applied != MirrorApply.STALE) {
                accountEventPublisher.publishBanned(userId);
            }
        }

        if (restored > 0) {
            log.warn("밴 미러 재적재 — 정본에 유효한 밴이 있는데 미러에 없던 사용자가 있었다(그동안 집행되지"
                    + " 않았다). 유효={} 복원={} actor={}", active, restored, command.actorId());
        } else {
            log.info("밴 미러 재적재 — 유효={} 복원=0 actor={}", active, command.actorId());
        }
        auditRecorder.record(new ChatAuditEntry(
                command.actorId(), actorRole, ChatAuditAction.CHAT_RECONCILE_BAN_MIRROR, null,
                Map.of("active", Integer.toString(active), "restored", Integer.toString(restored)),
                timeProvider.now()));
        return new ReconcileOutcome(active, restored);
    }
}
