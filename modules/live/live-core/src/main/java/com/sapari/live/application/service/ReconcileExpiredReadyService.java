package com.sapari.live.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.sapari.global.time.TimeProvider;
import com.sapari.live.application.port.ExpiredReadyReconcilePolicy;
import com.sapari.live.application.port.LiveMediaManager;
import com.sapari.live.command.ExpireOrphanLiveCommand;
import com.sapari.live.domain.exception.InvalidLiveStateException;
import com.sapari.live.domain.exception.LiveMediaException;
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
 * <p>판정은 <b>방을 처리하기 직전에 방마다</b> 한다. 회차 시작에 목록을 한 번 떠 두면, 그 뒤 순차 처리
 * 도중에 재연결한 방이 스냅샷에 없어 그대로 만료된다. 호출이 후보 수만큼 늘지만 후보는 보통 0건이고,
 * 후보가 있으면 어차피 방마다 정리 3종을 부른다.
 *
 * <p>{@code isIngressActive} 가 아니라 {@code isPublishingOrThrow} 를 쓰는 것도 의도다 — 전자는 조회 실패 시
 * {@code false} 라 go-live 기준으로는 안전하지만, 여기서는 "만료해라"로 읽혀 정반대가 된다.
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

        int expired = 0;
        int promoted = 0;
        int skipped = 0;
        for (UUID roomId : roomIds) {
            try {
                // 처리 직전에 확인한다 — 조회가 실패하면 그 방은 만료하지 않고 다음 회차로 미룬다.
                if (liveMediaManager.isPublishingOrThrow(roomId)) {
                    promoted += promote(roomId);
                } else {
                    expireOrphanLiveUseCase.expire(new ExpireOrphanLiveCommand(roomId));
                    expired++;
                }
            } catch (LiveMediaException e) {
                // 이 방만 다음 회차로 미룬다 — 던지고 끝내면 후보가 updated_at ASC 정렬이라 조회가
                // 재현성 있게 실패하는 방이 늘 선두에 서서 뒤 후보 전체가 영영 처리되지 않는다.
                // 전역 LiveKit 장애면 어차피 전 방이 여기로 빠져 아무것도 만료되지 않는다(fail-closed 유지).
                skipped++;
                log.warn("Ready 정리 스킵 — 송출 여부 조회 실패. roomId={}", roomId, e);
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

}
