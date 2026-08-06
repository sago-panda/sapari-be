package com.sapari.liveapp.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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

    @Scheduled(cron = "${live.reconcile.end-stale-live.cron:0 3/10 * * * *}")
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
