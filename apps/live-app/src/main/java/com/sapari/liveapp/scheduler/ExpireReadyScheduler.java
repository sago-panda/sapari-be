package com.sapari.liveapp.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.sapari.live.port.ReconcileExpiredReadyUseCase;

/**
 * OBS 가 끝내 연결되지 않아 Ready 에 갇힌 방을 만료시키는 트리거.
 * 판정·정책·루프는 모두 {@link ReconcileExpiredReadyUseCase} 에 있고 여기서는 시각만 옮긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "live.reconcile.expire-ready", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ExpireReadyScheduler {

    private final ReconcileExpiredReadyUseCase reconcileExpiredReadyUseCase;

    @Scheduled(cron = "${live.reconcile.expire-ready.cron:0 0/10 * * * *}")
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
