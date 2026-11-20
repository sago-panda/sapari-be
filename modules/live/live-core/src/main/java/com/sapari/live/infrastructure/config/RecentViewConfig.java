package com.sapari.live.infrastructure.config;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.sapari.live.application.port.LiveMetrics;
import com.sapari.live.application.port.RecentViewRecorder;
import com.sapari.live.application.service.RecentViewWriter;
import com.sapari.live.infrastructure.recentview.AsyncRecentViewRecorder;

/**
 * 최근 본 기록 전용 실행기. 스레드 수(기본 2) = 기록이 동시에 쥘 수 있는 커넥션 상한이다 — DB 장애 중에도 상태
 * 전이들이 쓰는 풀(Hikari 기본 10)을 기록이 다 먹지 못한다. 대기열(기본 1,000)을 넘는 기록은 버리고 센다
 * ({@code REJECTED}). 둘 다 {@link RecentViewProperties} 로 조정한다.
 *
 * <p><b>⚠️ 실행기를 빈으로 등록하지 않는다.</b> 부트의 기본 {@code applicationTaskExecutor} 는
 * {@code Executor} 빈이 하나라도 있으면 물러난다 — 여기서 빈으로 내놓으면 앱의 다른 비동기 처리가 조용히 이
 * 2스레드 풀로 몰린다. 그래서 이 설정이 실행기를 소유하고 종료도 직접 한다.
 *
 * <p>종료 시 대기열 잔여분은 최대 5초 기다리고 나머지는 잃는다 — 다음 입장에서 다시 기록된다.
 *
 * <p><b>{@link RecentViewWriter} 를 생성자로 받는 것은 소멸 순서 때문이다.</b> 스프링은 의존하는 빈을 먼저 소멸시킨다 —
 * 이 설정이 writer(→ 저장소 → EntityManagerFactory → DataSource)에 의존해야 실행기가 그들보다 먼저 닫혀, 남은 쓰기가
 * 살아 있는 풀에서 끝난다. 팩토리 메서드 인자로만 받으면 의존이 등록되지 않아 풀이 먼저 닫힐 수 있고, 그러면 배포마다
 * 잔여분이 {@code FAILED} 로 세어져 DB 장애처럼 보인다.
 */
@Configuration
@EnableConfigurationProperties(RecentViewProperties.class)
public class RecentViewConfig {

    private static final int SHUTDOWN_WAIT_SECONDS = 5;

    private final ThreadPoolTaskExecutor executor;
    private final RecentViewWriter writer;

    public RecentViewConfig(RecentViewWriter writer, RecentViewProperties properties) {
        this.writer = writer;
        this.executor = newExecutor(properties);
    }

    @Bean
    public RecentViewRecorder recentViewRecorder(ObjectProvider<LiveMetrics> metricsProvider) {
        // 관측 부재가 기록을 못 뜨게 하면 안 된다 — 슬라이스에서 LiveMetricsConfig 가 빠질 수 있다.
        return new AsyncRecentViewRecorder(executor, writer::write,
                metricsProvider.getIfAvailable(() -> LiveMetrics.NOOP), System::nanoTime);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    private static ThreadPoolTaskExecutor newExecutor(RecentViewProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.threads());
        executor.setMaxPoolSize(properties.threads());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setThreadNamePrefix("recent-view-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(SHUTDOWN_WAIT_SECONDS);
        executor.initialize();
        return executor;
    }
}
