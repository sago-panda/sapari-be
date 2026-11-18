package com.sapari.live.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sapari.global.time.TimeProvider;
import com.sapari.live.application.port.LiveMetrics;
import com.sapari.live.application.port.ReconcileAction;
import com.sapari.live.application.port.ReconcileJob;
import com.sapari.live.domain.repository.LiveRoomRepository;
import com.sapari.live.port.RecoverStuckReplaysUseCase;

/**
 * 종료 {@link #GRACE} 가 지나도 NONE/PROCESSING 인 다시보기를 READY 로 올린다 — 조건부 UPDATE 한 문장이고 LiveKit 은
 * 부르지 않는다. 그 시간이면 정상 egress 는 끝 상태에 닿는다.
 *
 * <p>감수한 한계: 녹화가 실제로는 실패했는데 그 알림까지 놓쳤다면 불완전한 영상도 READY 가 된다(SPR-159 이전과
 * 같은 수준). 종료 뒤 egress 중단이 커밋 후 실패해 고아 미디어 잡이 멈추기 전이면 ENDLIST 없는 아카이브도 올라간다.
 * 복구 뒤 늦게 온 FAILED 는 0행이라 debug 로그로만 남는다. 정확히 가르려면 LiveKit 에 egress 를 물어야 하지만,
 * 백필도 같은 전제를 받아들였다.
 *
 * <p>방치된 Live 방 정리 잡({@link ReconcileJob#END_STALE_LIVE})이 같은 락 아래 함께 돌린다. 새 잡·락을 두지 않으려고
 * 얹었으므로, 그 잡을 끄면 이 복구도 멈춘다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecoverStuckReplaysService implements RecoverStuckReplaysUseCase {

    static final Duration GRACE = Duration.ofMinutes(15);

    private final LiveRoomRepository liveRoomRepository;
    private final TimeProvider timeProvider;
    private final LiveMetrics liveMetrics;

    @Override
    @Transactional
    public int recover() {
        int recovered;
        try {
            recovered = liveRoomRepository.recoverStuckReplays(timeProvider.now().minus(GRACE));
        } catch (RuntimeException e) {
            // 세고 다시 던진다 — 스케줄러는 로그만 남기므로 여기서 세지 않으면 매 회차 실패가 "복구 0건" 으로 보인다.
            liveMetrics.reconcileActed(ReconcileJob.END_STALE_LIVE, ReconcileAction.REPLAY_RECOVERY_FAILED, 1);
            throw e;
        }
        liveMetrics.reconcileActed(ReconcileJob.END_STALE_LIVE, ReconcileAction.REPLAY_RECOVERY_FAILED, 0);
        liveMetrics.reconcileActed(ReconcileJob.END_STALE_LIVE, ReconcileAction.REPLAY_RECOVERED, recovered);
        if (recovered > 0) {
            log.warn("녹화 종료 알림을 놓친 다시보기 {}건을 READY 로 올림 — egress 웹훅 유실", recovered);
        }
        return recovered;
    }
}
