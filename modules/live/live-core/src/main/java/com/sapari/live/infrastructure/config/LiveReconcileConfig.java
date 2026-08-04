package com.sapari.live.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.sapari.live.application.port.OrphanMediaReconcilePolicy;

/**
 * 고아 라이브 정리 정책 바인딩. 설정을 application 의 policy record 로 바꿔 주입한다 —
 * 서비스가 {@code @ConfigurationProperties} 를 직접 받으면 application → infrastructure 의존이 된다.
 * 잡을 실제로 켜는 건 스케줄러(live-app)다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LiveReconcileProperties.class)
public class LiveReconcileConfig {

    @Bean
    public OrphanMediaReconcilePolicy orphanMediaReconcilePolicy(LiveReconcileProperties properties) {
        return new OrphanMediaReconcilePolicy(properties.orphanMedia().grace());
    }
}
