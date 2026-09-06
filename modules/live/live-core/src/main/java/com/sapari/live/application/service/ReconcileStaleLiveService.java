package com.sapari.live.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.sapari.global.time.TimeProvider;
import com.sapari.live.application.port.EgressSummary;
import com.sapari.live.application.port.LiveMediaManager;
import com.sapari.live.application.port.LiveMetrics;
import com.sapari.live.application.port.ReconcileAbortReason;
import com.sapari.live.application.port.ReconcileAction;
import com.sapari.live.application.port.ReconcileJob;
import com.sapari.live.application.port.StaleLiveReconcilePolicy;
import com.sapari.live.command.EndStaleLiveCommand;
import com.sapari.live.domain.exception.InvalidLiveStateException;
import com.sapari.live.domain.exception.LiveMediaException;
import com.sapari.live.domain.exception.LiveNotFoundException;
import com.sapari.live.domain.repository.LiveRoomRepository;
import com.sapari.live.port.EndStaleLiveUseCase;
import com.sapari.live.port.ReconcileStaleLiveUseCase;

/**
 * 방치된 Live 방 정리 — 오래된 Live 방 중 <b>활성 egress 가 없는</b> 방만 종료한다.
 *
 * <p>경과 시간은 후보를 좁힐 뿐 판정이 아니다. 정상적으로 오래 진행 중인 방송도 똑같이 오래됐으므로,
 * 시간만으로 끊으면 멀쩡한 방송을 죽인다. 실제 판정은 "LiveKit 에 이 방의 egress 가 살아 있는가"다.
 *
 * <p><b>시청자 수로 판단하지 말 것</b> — HLS 시청자는 SFU 참가자가 아니라 인기 방송도 0 으로 보이고,
 * 시청자 0 인 방송은 그 자체로 정상이다. egress 는 서버 측 녹화라 시청자 수와 무관하게 돈다.
 *
 * <p>종료는 방마다 {@link EndStaleLiveUseCase}(별도 빈)에 위임한다 — 방별 트랜잭션·행 잠금이 필요해
 * 같은 클래스의 private 메서드로 두면 self-invocation 이라 {@code @Transactional} 이 걸리지 않는다.
 * 여기에는 트랜잭션을 두지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconcileStaleLiveService implements ReconcileStaleLiveUseCase {

    private final LiveRoomRepository liveRoomRepository;
    private final LiveMediaManager liveMediaManager;
    private final EndStaleLiveUseCase endStaleLiveUseCase;
    private final StaleLiveReconcilePolicy policy;
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
                liveMetrics.reconcileRoundFailed(ReconcileJob.END_STALE_LIVE);
            }
        }
    }

    private void doReconcile() {
        Instant startedAt = timeProvider.now();
        Instant threshold = startedAt.minus(policy.threshold());

        List<UUID> candidates = liveRoomRepository.findStaleLiveRoomIds(threshold, policy.batchSize());
        if (candidates.isEmpty()) {
            // 후보 0건도 완료 회차다 — 기록하지 않으면 "할 일이 없던 회차" 와 "잡이 아예 안 돈 회차" 가
            // 똑같이 무기록이 되어, 스케줄러가 죽은 걸 알아챌 방법이 사라진다.
            liveMetrics.reconcileRoundCompleted(ReconcileJob.END_STALE_LIVE, elapsed(startedAt));
            return;
        }

        // 조회 실패는 예외로 올라온다 — 빈 목록으로 보이면 "모든 방이 죽었다"가 되어 전부 종료시킨다.
        Set<UUID> roomsWithActiveEgress = liveMediaManager.listAllEgress().stream()
                .filter(EgressSummary::active)
                .map(egress -> LiveKitRoomNames.parseRoomId(egress.roomName()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // 공집합 sanity 가드. listAllEgress 는 전송 실패만 예외로 올린다 — host/apiKey 오설정은 200 + [] 로
        // 온다. 그러면 "전 방이 죽었다"로 읽혀 후보 전량이 Ended 가 되고, 15분 뒤 고아 미디어 잡이 그
        // Ended 방들의 실제 송출 ingress 를 지운다(그 잡은 Ended 면 publishing 이어도 지우는 게 옳다).
        // 후보가 있는데 활성 egress 가 하나도 없는 건 정상 운영에서 나올 수 있는 조합이 아니므로 회차를 접는다.
        // 오설정이면 다음 회차에도 같은 답이 오니 미루는 비용이 없고, 진짜로 전부 죽었어도 다음 회차가 줍는다.
        // 이 값은 개별 방의 종료 판정 근거가 아니라, 공집합 가드가 어떤 클러스터 관측치에서
        // 통과하거나 중단됐는지 되짚는 수단이다. 개별 판정은 루프 안의 방별 최신 조회가 맡는다.
        liveMetrics.liveKitActiveEgressRooms(roomsWithActiveEgress.size());

        if (roomsWithActiveEgress.isEmpty()) {
            liveMetrics.reconcileRoundAborted(
                    ReconcileJob.END_STALE_LIVE, ReconcileAbortReason.NO_ACTIVE_EGRESS_CLUSTER_WIDE);
            log.error("방치 Live 정리 중단 — 후보 {}건인데 활성 egress 가 0건. LiveKit 오설정(200+빈 목록) 의심.",
                    candidates.size());
            return;
        }

        int ended = 0;
        int skipped = 0;
        int egressLookupFailed = 0;
        int egressSnapshotMismatch = 0;
        int spared = 0;
        // 집계는 finally 에서 — 루프 도중 예외로 빠지면 이미 종료시킨 방 수가 기록되지 않는다.
        // 이 잡은 살아 있는 방송을 끄는 잡이라, "죽기 전까지 몇 건을 껐나" 가 특히 중요하다.
        try {
        for (UUID roomId : candidates) {
            List<EgressSummary> roomEgresses;
            try {
                // 회차 시작 목록은 공집합 가드일 뿐 판정 스냅샷이 아니다. 후보를 건드리기 직전에 다시 봐야
                // 긴 회차 도중 재연결해 egress 가 살아난 방송을 오래된 목록으로 종료하지 않는다.
                roomEgresses = liveMediaManager.listRoomEgress(roomId);
            } catch (LiveMediaException e) {
                // 이 방만 다음 회차로 미룬다. started_at ASC 선두 방의 조회 실패가 재현되더라도 뒤 후보는
                // 계속 처리한다. 전역 조회 실패는 루프 전에 그대로 전파되므로 회차 장애 지표도 유지된다.
                egressLookupFailed++;
                log.warn("방치 Live 종료 스킵 — 송출 여부 조회 실패. roomId={}", roomId, e);
                continue;
            }
            // 전역 목록에는 이 방의 활성 egress 가 있었는데 방별 목록만 비면 부분 권한·라우팅 오설정이나
            // 일시적 불일치일 수 있다. 실제 종료 직후여도 다음 회차가 다시 줍는 비용만 내고 이번에는 건드리지 않는다.
            if (roomsWithActiveEgress.contains(roomId) && roomEgresses.isEmpty()) {
                egressSnapshotMismatch++;
                log.warn("방치 Live 종료 스킵 — 전역/방별 egress 조회 불일치. roomId={}", roomId);
                continue;
            }
            boolean hasActiveEgress = roomEgresses.stream().anyMatch(EgressSummary::active);
            if (hasActiveEgress) {
                spared++;
                continue; // 송출이 살아 있다 — 오래됐을 뿐 정상 방송
            }
            try {
                endStaleLiveUseCase.endStale(new EndStaleLiveCommand(roomId));
                ended++;
            } catch (InvalidLiveStateException | LiveNotFoundException e) {
                // 후보 조회~잠금 사이에 판매자가 직접 종료했거나 방이 사라진 경우. 다음 회차에 자연히 빠진다.
                skipped++;
                log.info("방치 Live 종료 스킵 — 이미 처리된 방. roomId={}, 사유={}", roomId, e.getClass().getSimpleName());
            }
        }
        } finally {
            liveMetrics.reconcileActed(ReconcileJob.END_STALE_LIVE, ReconcileAction.ENDED, ended);
            liveMetrics.reconcileActed(ReconcileJob.END_STALE_LIVE, ReconcileAction.SPARED, spared);
            liveMetrics.reconcileActed(ReconcileJob.END_STALE_LIVE, ReconcileAction.SKIPPED, skipped);
            liveMetrics.reconcileActed(
                    ReconcileJob.END_STALE_LIVE,
                    ReconcileAction.SKIPPED_EGRESS_LOOKUP_FAILED,
                    egressLookupFailed);
            liveMetrics.reconcileActed(
                    ReconcileJob.END_STALE_LIVE,
                    ReconcileAction.SKIPPED_EGRESS_SNAPSHOT_MISMATCH,
                    egressSnapshotMismatch);
        }
        liveMetrics.reconcileRoundCompleted(ReconcileJob.END_STALE_LIVE, elapsed(startedAt));
        log.info("방치된 Live 방 정리 완료. 후보={}, 종료={}, 송출중스킵={}, 이미처리={}, 조회실패={}, 조회불일치={}, 가드활성egress방수={}",
                candidates.size(), ended, spared, skipped, egressLookupFailed, egressSnapshotMismatch,
                roomsWithActiveEgress.size());
    }

    private Duration elapsed(Instant startedAt) {
        return Duration.between(startedAt, timeProvider.now());
    }

}
