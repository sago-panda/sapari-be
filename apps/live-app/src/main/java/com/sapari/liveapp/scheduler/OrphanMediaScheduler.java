package com.sapari.liveapp.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.sapari.live.port.ReconcileOrphanMediaUseCase;

/**
 * DB 정본과 어긋난 LiveKit ingress/egress 를 회수하는 트리거.
 * 살아 있는 egress 는 계속 과금되므로, 이 잡이 멈추면 비용이 조용히 샌다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = {"live.reconcile.enabled", "live.reconcile.orphan-media.enabled"},
        havingValue = "true", matchIfMissing = true)
public class OrphanMediaScheduler {

    private final ReconcileOrphanMediaUseCase reconcileOrphanMediaUseCase;

    /**
     * 잡별 락. 유지 시간의 의미와 {@code lock-at-least-for} 는 {@link com.sapari.liveapp.config.ReconcileLockConfig} 참고.
     *
     * <p><b>이 잡에는 "최악보다 길게"를 만족하는 고정값이 없다.</b> 배치 개념이 없어 LiveKit 목록
     * 전체를 순회하며 건당 삭제/중단을 부르므로 회차 길이가 리소스 수에 비례한다. {@code PT60M} 은
     * {@code callTimeout} 15s 기준 약 240회분이며, <b>장애 복구 직후처럼 고아가 쌓인 회차</b>
     * — 즉 이 잡이 가장 중요한 순간 — 에는 초과할 수 있다. 초과하면 락이 만료돼 다음 tick 의 다른
     * 인스턴스가 같은 스윕을 겹쳐 돌고, {@code reconcileActed} 가 배로 부풀어 이 잡의 판독법이 깨진다.
     * 근본 해결은 회차에 상한을 두거나 루프 중 락을 연장하는 것이고, 둘 다 이 티켓 범위 밖이다.
     */
    @Scheduled(cron = "${live.reconcile.orphan-media.cron:0 6/10 * * * *}")
    @SchedulerLock(name = "live-reconcile-orphan-media",
            lockAtMostFor = "${live.reconcile.orphan-media.lock-at-most-for:PT60M}",
            lockAtLeastFor = "${live.reconcile.lock-at-least-for:PT1M}")
    public void run() {
        try {
            reconcileOrphanMediaUseCase.reconcile();
        } catch (RuntimeException e) {
            // 삼키려는 게 아니라 도메인 문구를 남기려는 것 — 스케줄러가 최상위라 던져도 받을 곳이 없고,
            // Spring 기본 핸들러 로그는 어느 잡이 왜 실패했는지 알려주지 않는다. 회복은 다음 회차가 한다.
            log.error("고아 미디어 정리 실패 — 이번 회차 건너뜀", e);
        }
    }
}
