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
     * (LiveKit 클라이언트에 {@code callTimeout} 이 없어 한 회차가 길어질 수 있다).
     * yml 이 아니라 코드로 박는 건 {@code application*.yml} 이 추적되지 않아 환경마다 누락되기 때문이다.
     */
    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(POOL_SIZE);
        scheduler.setThreadNamePrefix("live-reconcile-");
        // 종료 시 진행 중인 회차를 기다린다 — 미디어 정리 도중에 끊기면 고아가 그대로 남는다.
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }
}
