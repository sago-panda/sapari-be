package com.sapari.liveapp.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 고아 라이브 정리 스케줄링. {@code live.reconcile.enabled=false} 로 세 잡을 한 번에 내릴 수 있다
 * (잡 하나만 끄는 건 각 스케줄러의 {@code @ConditionalOnProperty}).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "live.reconcile", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {

    /** 정리 잡 수. 셋이 서로를 막지 않으려면 스레드도 그만큼 있어야 한다. */
    private static final int POOL_SIZE = 3;

    /**
     * 기본 스케줄러는 스레드가 1개라 한 잡이 LiveKit 응답을 기다리는 동안 나머지가 밀린다
     * (호출마다 {@code callTimeout} 15s 가 걸려 있어도, 회차는 후보 수만큼 그게 반복된다).
     * yml 이 아니라 코드로 박는 건 {@code application*.yml} 이 추적되지 않아 환경마다 누락되기 때문이다.
     */
    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(POOL_SIZE);
        scheduler.setThreadNamePrefix("live-reconcile-");
        // 종료 시 진행 중인 회차를 기다린다 — 미디어 정리 도중에 끊기면 고아가 그대로 남는다.
        // 다만 보장은 아니다: 한 회차가 배치 크기 × (조회 + 정리) 왕복이고 각 호출이 최대 callTimeout 15s
        // 라 30초를 쉽게 넘는다. 파드 종료 유예(기본 30s)도 그쯤이다.
        //
        // 여기서 못 끝낸 회차를 "다음 회차가 줍는다"고 읽지 말 것 — SPR-142 의 분산 락 이후로 틀렸다.
        // 유예를 넘겨 죽은 파드는 락을 정상 반납하지 못하므로, 그 잡은 <b>리스가 만료될 때까지</b> 어느
        // 인스턴스에서도 돌지 않는다(end-stale-live 기준 최대 90분). 롤링 배포가 회차 중간에 걸릴 때마다
        // 재현된다. 값은 잡별 lock-at-most-for 이고 근거는 각 스케줄러 자바독에 있다.
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }
}
