package com.sapari.liveapp.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.sapari.live.port.RefreshLiveListUseCase;

/**
 * 공개 방송 중 목록 재조정 트리거(15초 간격).
 *
 * <p><b>분산 락을 걸지 않는다.</b> 회차가 멱등(DB 를 원본으로 덮어쓰기)이라 인스턴스마다 돌아도 결과가 같고,
 * ShedLock 의 {@code lock-at-least-for}(1m) 가드가 15초 주기와 맞지 않는다. 비용은 인스턴스 수만큼의 읽기다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "live.list.refresh.enabled", havingValue = "true", matchIfMissing = true)
public class LiveListRefreshScheduler {

    private final RefreshLiveListUseCase refreshLiveListUseCase;

    @Scheduled(fixedDelayString = "${live.list.refresh.interval:PT15S}",
            initialDelayString = "${live.list.refresh.interval:PT15S}")
    public void run() {
        try {
            refreshLiveListUseCase.refresh();
        } catch (RuntimeException e) {
            // 정리 잡 트리거와 같은 판단 — 스케줄러가 최상위라 던져도 받을 곳이 없다. 다음 회차가 다시 맞춘다.
            log.error("방송 중 목록 재조정 실패 — 이번 회차 건너뜀", e);
        }
    }
}
