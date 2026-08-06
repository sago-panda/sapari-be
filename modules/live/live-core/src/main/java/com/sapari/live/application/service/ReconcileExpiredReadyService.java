package com.sapari.live.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.sapari.global.time.TimeProvider;
import com.sapari.live.application.port.ExpiredReadyReconcilePolicy;
import com.sapari.live.application.port.IngressSummary;
import com.sapari.live.application.port.LiveMediaManager;
import com.sapari.live.command.ExpireOrphanLiveCommand;
import com.sapari.live.domain.exception.InvalidLiveStateException;
import com.sapari.live.domain.exception.LiveNotFoundException;
import com.sapari.live.domain.model.LiveStatus;
import com.sapari.live.domain.repository.LiveRoomRepository;
import com.sapari.live.port.ExpireOrphanLiveUseCase;
import com.sapari.live.port.ReconcileExpiredReadyUseCase;

/**
 * Ready 고착 방 정리 — 오래된 Ready 방을 만료시키되, <b>OBS 가 실제로 송출 중인 방은 만료가 아니라
 * 뒤늦게 Live 로 승격</b>시킨다.
 *
 * <p>승격 분기가 필요한 이유: 이 잡의 주 표적 중 하나가 "OBS 선연결 + {@code isIngressActive} 조회 실패"로
 * {@code ingress_started} 랑데부를 놓친 방인데(재전송될 이벤트가 없다), 그 방은 <b>지금도 publish 중</b>이다.
 * 만료시키면 ingress 를 지우고 SFU 방을 닫아 판매자 송출을 끊는다. 실패한 랑데부를 여기서 완성하는 게 맞다.
 *
 * <p>판정을 {@code isIngressActive}(방마다 1회)로 하지 않는 것도 의도다 — 그 메서드는 조회 실패 시
 * {@code false} 를 주는데, go-live 기준으로는 안전한 방향이지만 여기서는 "만료해라"로 읽혀 정반대가 된다.
 * {@code listAllIngress}(실패 시 예외)를 회차당 1회 부르면 LiveKit 장애 때 아무 방도 건드리지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconcileExpiredReadyService implements ReconcileExpiredReadyUseCase {

    private final LiveRoomRepository liveRoomRepository;
    private final LiveMediaManager liveMediaManager;
    private final ExpireOrphanLiveUseCase expireOrphanLiveUseCase;
    private final GoLiveByRtmpService goLiveByRtmpService;
    private final ExpiredReadyReconcilePolicy policy;
    private final TimeProvider timeProvider;

    @Override
    public void reconcile() {
        Instant threshold = timeProvider.now().minus(policy.threshold());
        List<UUID> roomIds = liveRoomRepository.findExpiredReadyRoomIds(threshold, policy.batchSize());
        if (roomIds.isEmpty()) {
            return;
        }

        Set<UUID> publishing = publishingRoomIds();

        int expired = 0;
        int promoted = 0;
        int skipped = 0;
        for (UUID roomId : roomIds) {
            try {
                if (publishing.contains(roomId)) {
                    promoted += promote(roomId);
                } else {
                    expireOrphanLiveUseCase.expire(new ExpireOrphanLiveCommand(roomId));
                    expired++;
                }
            } catch (InvalidLiveStateException | LiveNotFoundException e) {
                skipped++;
                log.info("Ready 정리 스킵 — 이미 처리된 방. roomId={}, 사유={}", roomId, e.getClass().getSimpleName());
            }
        }
        log.info("고아 Ready 방 정리 완료. 후보={}, 만료={}, 승격={}, 스킵={}",
                roomIds.size(), expired, promoted, skipped);
    }

    /**
     * 놓친 랑데부를 뒤늦게 완성한다. {@code goLiveByRtmp} 는 멱등하고 행 잠금을 잡는다.
     *
     * <p>여기서만 예외를 넓게 잡는 이유: 승격은 {@code startHlsEgress} 까지 부르는 유일한 경로라
     * {@code BroadcastStartException}·{@code LiveMediaException} 을 던질 수 있는데, 후보 조회가
     * {@code updated_at ASC} 정렬이라 그대로 두면 실패하는 방이 늘 목록 선두에 서서 뒤 후보 전체가
     * 매 회차 처리되지 못한다(head-of-line blocking). 만료 경로는 넓히지 않는다 — 거기서 DB 장애가
     * 나면 회차를 멈추는 게 맞다.
     *
     * @return 실제로 Live 로 전이했으면 1, 아니면 0
     */
    private int promote(UUID roomId) {
        try {
            // 전이 여부를 반환하지 않으므로(no-op 이어도 조용히 끝난다) 저장된 상태로 확인한다.
            goLiveByRtmpService.goLiveByRtmp(roomId);
        } catch (RuntimeException e) {
            log.error("Ready 고착 방 승격 실패 — 다음 회차 재시도. roomId={}", roomId, e);
            return 0;
        }
        boolean nowLive = liveRoomRepository.findById(roomId)
                .map(room -> room.status() instanceof LiveStatus.Live)
                .orElse(false);
        if (!nowLive) {
            // Ready+WebRtc 방에 매핑되는 ingress 가 있으면 승격도 만료도 되지 않고 후보에 계속 남는다.
            log.warn("Ready 고착 방 승격 no-op — 만료도 승격도 되지 않는 방. roomId={}", roomId);
            return 0;
        }
        log.warn("Ready 고착 방이 송출 중 — 만료 대신 Live 로 승격. roomId={}", roomId);
        return 1;
    }

    /** 지금 송출 중인 방 — 조회가 실패하면 예외가 올라가 회차 전체가 중단된다(빈 집합이면 전부 만료된다). */
    private Set<UUID> publishingRoomIds() {
        return liveMediaManager.listAllIngress().stream()
                .filter(IngressSummary::publishing)
                .map(ingress -> LiveKitRoomNames.parseRoomId(ingress.roomName()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }
}
