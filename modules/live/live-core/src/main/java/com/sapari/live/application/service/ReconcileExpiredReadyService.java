package com.sapari.live.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.sapari.global.time.TimeProvider;
import com.sapari.live.application.port.ExpiredReadyReconcilePolicy;
import com.sapari.live.application.port.IngressSummary;
import com.sapari.live.application.port.LiveMediaManager;
import com.sapari.live.application.port.LiveMetrics;
import com.sapari.live.application.port.PromotionTrigger;
import com.sapari.live.application.port.ReconcileAction;
import com.sapari.live.application.port.ReconcileJob;
import com.sapari.live.command.ExpireOrphanLiveCommand;
import com.sapari.live.domain.exception.InvalidLiveStateException;
import com.sapari.live.domain.exception.LiveMediaException;
import com.sapari.live.domain.exception.LiveNotFoundException;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.LiveStatus;
import com.sapari.live.domain.repository.LiveRoomRepository;
import com.sapari.live.port.ExpireOrphanLiveUseCase;
import com.sapari.live.port.ReconcileExpiredReadyUseCase;

/**
 * Ready 고착 방 정리 — 오래된 Ready 방을 만료시키되, <b>OBS 가 실제로 송출 중인 방은 만료가 아니라
 * 뒤늦게 Live 로 승격</b>시킨다.
 *
 * <p>승격 분기가 필요한 이유: 이 잡의 주 표적 중 하나가 "OBS 선연결 + {@code publishingIngressIdsOrEmpty} 조회 실패"로
 * {@code ingress_started} 랑데부를 놓친 방인데(재전송될 이벤트가 없다), 그 방은 <b>지금도 publish 중</b>이다.
 * 만료시키면 ingress 를 지우고 SFU 방을 닫아 판매자 송출을 끊는다. 실패한 랑데부를 여기서 완성하는 게 맞다.
 *
 * <p>판정은 <b>방을 처리하기 직전에 방마다</b> 한다. 회차 시작에 목록을 한 번 떠 두면, 그 뒤 순차 처리
 * 도중에 재연결한 방이 스냅샷에 없어 그대로 만료된다. 호출이 후보 수만큼 늘지만 후보는 보통 0건이고,
 * 후보가 있으면 어차피 방마다 정리 3종을 부른다.
 *
 * <p>{@code publishingIngressIdsOrEmpty} 가 아니라 {@code listRoomIngress} 를 쓰는 것도 의도다 — 전자는 조회 실패 시
 * {@code false} 라 go-live 기준으로는 안전하지만, 여기서는 "만료해라"로 읽혀 정반대가 된다.
 *
 * <p>판정은 <b>세 갈래</b>다. 방이 인정하는 ingress 가 송출 중이면 승격, 송출이 아예 없으면 만료,
 * <b>송출은 있는데 이 방 것이 아니면 아무것도 하지 않는다</b>(경합 패자 잔존 — 승격도 만료도 둘 다 틀리다).
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
    private final LiveMetrics liveMetrics;

    /**
     * 회차를 감싸 예외를 <b>세고 다시 던진다</b>. 스케줄러가 잡아 로그를 남기는 구조는 그대로 두고
     * (실패 처리 방식을 바꾸지 않는다) 밖에서 보이는 사실만 하나 늘린다 — 이게 없으면 매 회차
     * 예외로 죽는 잡과 스케줄러가 아예 안 도는 상황이 지표상 똑같다.
     */
    @Override
    public void reconcile() {
        // 예외를 잡지 않는다 — 정상 종료에만 표시를 남기고, 없으면 실패로 센다. catch 로 세면
        // Error 로 죽은 회차가 무기록이 되어(= 스케줄러가 안 돈 것과 같아 보임) 이 지표를 만든
        // 이유가 사라지고, Throwable 을 잡으면 죽어가는 JVM 을 건드린다.
        boolean completed = false;
        try {
            doReconcile();
            completed = true;
        } finally {
            if (!completed) {
                liveMetrics.reconcileRoundFailed(ReconcileJob.EXPIRE_READY);
            }
        }
    }

    private void doReconcile() {
        Instant startedAt = timeProvider.now();
        Instant threshold = startedAt.minus(policy.threshold());
        List<UUID> roomIds = liveRoomRepository.findExpiredReadyRoomIds(threshold, policy.batchSize());
        if (roomIds.isEmpty()) {
            // 후보 0건도 완료 회차다 — 무기록으로 두면 스케줄러가 죽은 것과 구분되지 않는다.
            liveMetrics.reconcileRoundCompleted(ReconcileJob.EXPIRE_READY, elapsed(startedAt));
            return;
        }

        int expired = 0;
        int promoted = 0;
        int skipped = 0;
        int ingressMissing = 0;   // skipped 의 부분집합 — 오설정 신호라 따로 센다
        // 집계는 finally 에서 내보낸다 — 루프 도중 예외로 빠져나가면 그때까지 실제로 만료·승격한
        // 건수가 통째로 사라지고 failed 만 남는다. "죽기 전까지 N건은 처리했다" 가 사후 추적의 시작점이다.
        try {
        for (UUID roomId : roomIds) {
            try {
                // 처리 직전에 확인한다 — 조회가 실패하면 그 방은 만료하지 않고 다음 회차로 미룬다.
                List<IngressSummary> ingresses = liveMediaManager.listRoomIngress(roomId);
                LiveRoom room = liveRoomRepository.findById(roomId).orElse(null);
                if (room == null) {
                    skipped++;
                    log.info("Ready 정리 스킵 — 조회 사이에 사라진 방. roomId={}", roomId);
                    continue;
                }
                // LiveKit 이 이 방의 ingress 를 하나도 모르는데 DB 는 배정돼 있다고 한다 = 다른 클러스터를
                // 보고 있거나 오설정. 여기서 만료로 넘기면 살아 있는 방송이 회차당 batch-size 만큼 끊긴다.
                // "OBS 가 끝내 안 붙은 방"은 ingress 가 등록은 돼 있어(INACTIVE) 이 분기에 걸리지 않는다.
                if (ingresses.isEmpty() && room.isRtmp()) {
                    // 방 단위 판정이므로 회차 중단(aborted)이 아니라 별도 갈래로 센다. 여기서
                    // reconcileRoundAborted 를 부르면 회차 하나에서 후보 수만큼 올라 회차 지표가 깨진다.
                    ingressMissing++;
                    skipped++;
                    log.error("Ready 정리 스킵 — DB 는 ingress 배정을 아는데 LiveKit 목록이 빔. 오설정 의심. roomId={}",
                            roomId);
                    continue;
                }

                // 이 방이 인정하는 ingress 가 송출 중인지 본다. 목록에서 고르는 게 아니라 방에게 물어야 한다 —
                // 경합 패자의 회수가 실패하면 한 방에 송출 중인 ingress 가 둘일 수 있고, 그중 아무거나 집으면
                // 방이 인정하지 않은 ingress 로 방송을 시작시키거나(승격) 살아 있는 송출을 놓친다(만료).
                List<String> publishing = ingresses.stream()
                        .filter(IngressSummary::publishing)
                        .map(IngressSummary::ingressId)
                        .toList();
                String ownIngressId = publishing.stream().filter(room::hasIngress).findFirst().orElse(null);

                if (ownIngressId != null) {
                    promoted += promote(roomId, ownIngressId);
                } else if (publishing.isEmpty()) {
                    // 송출이 없다 = 만료 대상(WebRtc 방이거나 OBS 가 끝내 안 붙은 방).
                    expireOrphanLiveUseCase.expire(new ExpireOrphanLiveCommand(roomId));
                    expired++;
                } else {
                    // 송출은 있는데 이 방 것이 아니다 — 경합 패자 잔존이 전형. 승격하면 방이 인정 안 한
                    // ingress 가 방송을 시작하고, 만료하면 그 송출을 끊는다. 둘 다 틀리므로 손대지 않는다.
                    // 회수는 고아 미디어 잡의 몫 — 그 잡은 "방이 인정하지 않는 ingress"를 송출 중이어도
                    // 지우므로, 방이 Ended 가 되기를 기다리지 않는다(기다리면 서로를 기다리는 교착이 된다).
                    skipped++;
                    log.warn("Ready 정리 스킵 — 방이 인정하지 않는 ingress 가 송출 중. roomId={}, 송출중={}",
                            roomId, publishing);
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
        } finally {
            liveMetrics.reconcileActed(ReconcileJob.EXPIRE_READY, ReconcileAction.PROMOTED, promoted);
            liveMetrics.reconcileActed(ReconcileJob.EXPIRE_READY, ReconcileAction.EXPIRED, expired);
            liveMetrics.reconcileActed(
                    ReconcileJob.EXPIRE_READY, ReconcileAction.SKIPPED, skipped - ingressMissing);
            liveMetrics.reconcileActed(
                    ReconcileJob.EXPIRE_READY, ReconcileAction.SKIPPED_INGRESS_MISSING, ingressMissing);
        }
        liveMetrics.reconcileRoundCompleted(ReconcileJob.EXPIRE_READY, elapsed(startedAt));
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
     * <p><b>알려진 지표 편차</b>: 승격 여부를 잠금 없는 재조회로 판정하므로, webhook 이 같은 순간에 먼저
     * 전이시키면 여기서는 "승격됨"(→ {@code acted{promoted}})으로 세고 경로 태그는
     * {@code rtmp.promotion{trigger=webhook}} 으로 오른다. 두 지표가 1건 어긋난다. <b>업무 동작은
     * 정확하다</b> — 전이는 행 잠금 덕에 정확히 한 번만 일어난다. 없애려면 {@code goLiveByRtmp} 가 전이
     * 여부를 반환해야 하는데, 그 시그니처 변경은 랑데부 계약(세 경로가 같은 진입점을 쓴다)을 건드리는
     * 일이라 관측 편차 하나 때문에 치를 값이 아니다. 경합이 실제로 일어난 회차에만 생긴다.
     *
     * @return 실제로 Live 로 전이했으면 1, 아니면 0
     */
    private int promote(UUID roomId, String ingressId) {
        try {
            // 전이 여부를 반환하지 않으므로(no-op 이어도 조용히 끝난다) 저장된 상태로 확인한다.
            // webhook 과 같은 진입점을 쓴다 — 대조를 건너뛰는 별도 경로를 두면 그쪽만 가드가 빠진다.
            goLiveByRtmpService.goLiveByRtmp(roomId, ingressId, PromotionTrigger.RECONCILE);
        } catch (RuntimeException e) {
            log.error("Ready 고착 방 승격 실패 — 다음 회차 재시도. roomId={}", roomId, e);
            return 0;
        }
        boolean nowLive = liveRoomRepository.findById(roomId)
                .map(room -> room.status() instanceof LiveStatus.Live)
                .orElse(false);
        if (!nowLive) {
            // 여기까지 왔다는 건 방이 인정하는 ingress 가 송출 중이었다는 뜻이라 승격이 됐어야 한다.
            // 그런데 안 됐다면 조회~잠금 사이에 상태가 바뀐 것이다(판매자가 직접 종료 등). 다음 회차가 다시 본다.
            log.warn("Ready 고착 방 승격 no-op — 조회 이후 상태가 바뀐 것으로 보임. roomId={}", roomId);
            return 0;
        }
        log.warn("Ready 고착 방이 송출 중 — 만료 대신 Live 로 승격. roomId={}", roomId);
        return 1;
    }

    private Duration elapsed(Instant startedAt) {
        return Duration.between(startedAt, timeProvider.now());
    }
}
