package com.sapari.live.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import com.sapari.live.application.port.ExpiredReadyReconcilePolicy;
import com.sapari.live.application.port.OrphanMediaReconcilePolicy;
import com.sapari.live.application.port.StaleLiveReconcilePolicy;

/**
 * 고아 라이브 정리 정책 바인딩. 설정을 application 의 policy record 로 바꿔 주입한다 —
 * 서비스가 {@code @ConfigurationProperties} 를 직접 받으면 application → infrastructure 의존이 된다.
 * 잡을 실제로 켜는 건 스케줄러(live-app)다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LiveReconcileProperties.class)
public class LiveReconcileConfig {

    /**
     * 제거된 설정 키가 남아 있으면 <b>부팅을 실패시킨다</b>. 조용히 무시하면 운영자는 그 값이 먹는다고
     * 믿은 채로 지내고, 그 믿음이 깨지는 건 사고 때다.
     */
    @Bean
    public LegacyBatchSizeGuard legacyBatchSizeMustNotBeConfigured(Environment environment) {
        if (environment.containsProperty("live.reconcile.batch-size")) {
            throw new IllegalStateException("live.reconcile.batch-size 는 제거되었습니다. "
                    + "live.reconcile.end-stale-live.batch-size 와 live.reconcile.expire-ready.batch-size 를 설정하세요.");
        }
        if (environment.containsProperty("live.reconcile.orphan-media.batch-size")) {
            throw new IllegalStateException("live.reconcile.orphan-media.batch-size 는 제거되었습니다 — "
                    + "고아 정리는 개수가 아니라 시간(live.reconcile.orphan-media.lock-at-most-for 에서 "
                    + "파생한 회차 예산)으로 회차를 묶습니다. 처리량을 조절하려면 그 값을 조정하세요.");
        }
        return new LegacyBatchSizeGuard();
    }

    public static final class LegacyBatchSizeGuard { }

    @Bean
    public OrphanMediaReconcilePolicy orphanMediaReconcilePolicy(LiveReconcileProperties properties) {
        return new OrphanMediaReconcilePolicy(
                properties.orphanMedia().grace(), properties.orphanMedia().roundBudget());
    }

    @Bean
    public StaleLiveReconcilePolicy staleLiveReconcilePolicy(LiveReconcileProperties properties) {
        return new StaleLiveReconcilePolicy(
                properties.endStaleLive().threshold(),
                properties.endStaleLive().batchSize(),
                properties.endStaleLive().roundBudget());
    }

    @Bean
    public ExpiredReadyReconcilePolicy expiredReadyReconcilePolicy(LiveReconcileProperties properties){
        return new ExpiredReadyReconcilePolicy(
                properties.expireReady().threshold(),
                properties.expireReady().batchSize(),
                properties.expireReady().roundBudget());
    }
}
