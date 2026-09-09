package com.sapari.liveapp.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.sapari.liveapp.config.ReconcileLockConfig;
import com.sapari.liveapp.config.SchedulingConfig;

import com.sapari.live.port.ReconcileExpiredReadyUseCase;

/**
 * OBS 가 끝내 연결되지 않아 Ready 에 갇힌 방을 만료시키는 트리거.
 * 판정·정책·루프는 모두 {@link ReconcileExpiredReadyUseCase} 에 있고 여기서는 시각만 옮긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = {"live.reconcile.enabled", "live.reconcile.expire-ready.enabled"},
        havingValue = "true", matchIfMissing = true)
public class ExpireReadyScheduler {

    private final ReconcileExpiredReadyUseCase reconcileExpiredReadyUseCase;

    /**
     * 잡별 락. 유지 시간의 의미와 {@code lock-at-least-for} 는 {@link com.sapari.liveapp.config.ReconcileLockConfig} 참고.
     *
     * <p>절대 최악(모든 호출이 {@code callTimeout} 15s 를 다 쓰는 경우) = 후보
     * {@code expire-ready.batch-size} 20 × (송출 조회 1 + 만료 시 커밋 후 정리 <b>HTTP 8회</b>)
     * = 180회 × 15s ≈ 45분. 승격 경로는 {@code startHlsEgress} 3회라 더 짧다.
     *
     * <p>세 잡 중 <b>유일하게 절대 최악을 덮는</b> 잡이다 — 45분 + 여유로 {@code PT60M}. 세는 법과
     * 값 규칙은 {@link ReconcileLockConfig#LOCK_AT_MOST_FOR_EXPIRE_READY} 에 한 번만 적었다.
     */
    @Scheduled(cron = "${live.reconcile.expire-ready.cron:" + SchedulingConfig.EXPIRE_READY_CRON + "}")
    @SchedulerLock(name = "live-reconcile-expire-ready",
            lockAtMostFor = "${live.reconcile.expire-ready.lock-at-most-for:"
                    + ReconcileLockConfig.LOCK_AT_MOST_FOR_EXPIRE_READY + "}",
            lockAtLeastFor = "${live.reconcile.lock-at-least-for:" + ReconcileLockConfig.LOCK_AT_LEAST_FOR + "}")
    public void run() {
        try {
            reconcileExpiredReadyUseCase.reconcile();
        } catch (RuntimeException e) {
            // 삼키려는 게 아니라 도메인 문구를 남기려는 것 — 스케줄러가 최상위라 던져도 받을 곳이 없고,
            // Spring 기본 핸들러 로그는 어느 잡이 왜 실패했는지 알려주지 않는다. 회복은 다음 회차가 한다.
            log.error("Ready 고착 방 만료 실패 — 이번 회차 건너뜀", e);
        }
    }
}
