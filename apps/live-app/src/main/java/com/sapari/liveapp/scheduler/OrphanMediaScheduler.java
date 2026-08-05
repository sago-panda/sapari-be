package com.sapari.liveapp.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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

    @Scheduled(cron = "${live.reconcile.orphan-media.cron:0 6/10 * * * *}")
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
