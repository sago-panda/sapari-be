package com.sapari.liveapp.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.sapari.liveapp.config.ReconcileLockConfig;
import com.sapari.liveapp.config.SchedulingConfig;

import com.sapari.live.port.ReconcileStaleLiveUseCase;

/**
 * 방송은 끝났는데 Live 에 갇힌 방을 종료시키는 트리거.
 *
 * <p><b>세 정리 잡 중 유일하게 살아 있을 수도 있는 방송을 종료한다.</b> 오작동이 의심되면
 * {@code LIVE_RECONCILE_END_STALE_LIVE_ENABLED=false} 로 이 잡만 즉시 내릴 것 —
 * 나머지 둘(Ready 만료·고아 미디어)은 이미 끝난 리소스만 건드리므로 함께 멈출 이유가 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = {"live.reconcile.enabled", "live.reconcile.end-stale-live.enabled"},
        havingValue = "true", matchIfMissing = true)
public class EndStaleLiveScheduler {

    private final ReconcileStaleLiveUseCase reconcileStaleLiveUseCase;

    /**
     * 잡별 락. 유지 시간의 의미와 {@code lock-at-least-for} 는 {@link com.sapari.liveapp.config.ReconcileLockConfig} 참고.
     *
     * <p><b>상한 근거: 셋 중 가장 길다.</b> 이 잡만 공용 {@code batch-size} 100 을 쓰고, 종료된 방마다
     * {@code PostCommitMediaCleanup} 이 <b>회차 스레드에서 동기로</b> 최대 3회(egress 중단 → ingress
     * 삭제 → 방 삭제) LiveKit 을 부른다. 전역 egress 조회 1 + 100 × 3 = 301회 × {@code callTimeout} 15s
     * ≈ 75분. "DB 전이뿐이라 짧다"는 오독이다 — 전이 뒤에 붙는 정리가 회차 시간의 대부분이다.
     *
     * <p>그 대가로 이 잡의 인계가 최대 90분 늦는다. 줄이려면 값이 아니라 <b>회차를 묶어야</b> 한다
     * (이 잡 전용 {@code batch-size} 도입). 처리량을 바꾸는 변경이라 <b>[SPR-145 로 이월]</b> 했다.
     */
    @Scheduled(cron = "${live.reconcile.end-stale-live.cron:" + SchedulingConfig.END_STALE_LIVE_CRON + "}")
    @SchedulerLock(name = "live-reconcile-end-stale-live",
            lockAtMostFor = "${live.reconcile.end-stale-live.lock-at-most-for:PT90M}",
            lockAtLeastFor = "${live.reconcile.lock-at-least-for:" + ReconcileLockConfig.LOCK_AT_LEAST_FOR + "}")
    public void run() {
        try {
            reconcileStaleLiveUseCase.reconcile();
        } catch (RuntimeException e) {
            // 삼키려는 게 아니라 도메인 문구를 남기려는 것 — 스케줄러가 최상위라 던져도 받을 곳이 없고,
            // Spring 기본 핸들러 로그는 어느 잡이 왜 실패했는지 알려주지 않는다. 회복은 다음 회차가 한다.
            log.error("방치된 Live 방 종료 실패 — 이번 회차 건너뜀", e);
        }
    }
}
