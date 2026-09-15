package com.sapari.liveapp.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.sapari.liveapp.config.ReconcileLockConfig;
import com.sapari.liveapp.config.SchedulingConfig;

import com.sapari.live.application.port.ReconcileJob;
import com.sapari.live.infrastructure.config.LiveReconcileProperties;
import com.sapari.live.port.ReconcileOrphanMediaUseCase;

/**
 * DB 정본과 어긋난 LiveKit ingress/egress 를 회수하는 트리거.
 * 살아 있는 egress 는 계속 과금되므로, 이 잡이 멈추면 비용이 조용히 샌다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
/*
 * 이 스위치는 <b>빈 등록 조건</b>이지 런타임 토글이 아니다 — 끄려면 롤링 재시작이 필요하고, 이미
 * 도는 회차는 멈추지 않는다(최대 회차 예산만큼 더 돈다). 사고 대응 절차에서 이걸 "즉시 정지"로
 * 기대하면 안 된다: live_room.status 를 대량으로 건드리기 <b>전에</b> 재시작을 끝내 둘 것.
 */
@ConditionalOnProperty(name = {"live.reconcile.enabled", "live.reconcile.orphan-media.enabled"},
        havingValue = "true", matchIfMissing = true)
public class OrphanMediaScheduler {

    private final ReconcileOrphanMediaUseCase reconcileOrphanMediaUseCase;

    /**
     * 잡별 락. 유지 시간의 의미와 {@code lock-at-least-for} 는 {@link com.sapari.liveapp.config.ReconcileLockConfig} 참고.
     *
     * <p>목록 조회 3회 뒤 <b>방 단위로</b> 순회한다 — 한 방의 ingress 삭제 → egress 중단 → 방 닫기를
     * 한자리에서 그 순서로 처리한다(하나라도 남기고 방을 닫으면 OBS 가 방을 되살린다). 종류별 개수
     * 상한은 없다; 방당 왕복 수가 그 방의 리소스 수에 비례해 고정이 아니라 개수로는 회차를 묶을 수
     * 없기 때문이다. 정지 규칙은 리스에서 파생한 {@code roundBudget}(기본 20분의 4/5 = 16분) 하나뿐이고,
     * 마감에 걸린 방은 <b>닫지 않은 채</b> 다음 회차로 넘긴다. 락 연장은 적체가 클수록 죽은 인스턴스
     * 판정도 늦추므로 쓰지 않는다.
     */
    @Scheduled(cron = "${live.reconcile.orphan-media.cron:" + SchedulingConfig.ORPHAN_MEDIA_CRON + "}")
    @SchedulerLock(name = ReconcileJob.Locks.ORPHAN_MEDIA,
            lockAtMostFor = "${live.reconcile.orphan-media.lock-at-most-for:"
                    + LiveReconcileProperties.OrphanMedia.DEFAULT_LOCK_AT_MOST_FOR + "}",
            lockAtLeastFor = "${live.reconcile.lock-at-least-for:" + ReconcileLockConfig.LOCK_AT_LEAST_FOR + "}")
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
