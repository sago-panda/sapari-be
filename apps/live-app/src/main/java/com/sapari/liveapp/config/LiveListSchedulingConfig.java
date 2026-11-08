package com.sapari.liveapp.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 목록 재조정 스케줄링. {@link SchedulingConfig} 는 {@code live.reconcile.enabled} 에 묶여 있어, 정리 잡을 내려도
 * 목록 갱신은 돌아야 하므로 따로 켠다. 둘 다 켜지면 {@code @EnableScheduling} 이 겹쳐도 한 번만 적용된다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "live.list.refresh.enabled", havingValue = "true", matchIfMissing = true)
public class LiveListSchedulingConfig {
}
