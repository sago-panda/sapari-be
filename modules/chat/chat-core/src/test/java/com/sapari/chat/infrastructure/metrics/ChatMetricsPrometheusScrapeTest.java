package com.sapari.chat.infrastructure.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.chat.application.port.ChatCheckpoint;
import com.sapari.chat.application.port.ChatMetrics;
import com.sapari.chat.application.port.ChatPolicy;
import com.sapari.chat.application.port.DeliveryStage;
import com.sapari.chat.application.port.PolicyReadFailure;
import com.sapari.chat.application.port.PersistStage;
import com.sapari.chat.application.port.RateLimitDegradation;
import com.sapari.chat.application.port.SessionCutReason;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

/**
 * 실제 스크레이프에 모든 시계열이 나오는지 본다.
 *
 * <p>{@code SimpleMeterRegistry} 로는 못 잡는 실패가 있다 — 프로메테우스는 <b>같은 이름에 태그 키
 * 집합이 다른</b> 미터를 거부한다. 그러면 나중에 등록된 쪽이 <b>조용히 사라지고</b> 그 사실은
 * 운영에서만 드러난다. live 에서 실제로 그 일이 있었다(중단 시계열만 태그가 하나 더 있어 스크레이프에
 * 안 나왔다 — 하필 "가드가 회차를 삼켰다"는 유일한 외부 신호였다).
 *
 * <p>{@code chat.policy.open} 이 태그 셋을 다는 것이 그 함정에 가장 가깝다 — 호출을 하나 추가할 때
 * 태그를 빼먹으면 그 계열만 사라진다. 지표를 늘릴 때 여기에 한 줄씩 늘릴 것.
 */
@DisplayName("프로메테우스 스크레이프 — 태그 키 집합이 어긋나면 계열이 조용히 사라진다")
class ChatMetricsPrometheusScrapeTest {

    private PrometheusMeterRegistry registry;
    private ChatMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        metrics = new MicrometerChatMetrics(registry);
    }

    @Test
    @DisplayName("판정 통과 여섯 조합이 모두 스크레이프에 나온다")
    void everyPolicyOpenCombinationSurvivesScrape() {
        // when: 실제로 쓰이는 조합 전부
        for (ChatPolicy policy : ChatPolicy.values()) {
            for (ChatCheckpoint checkpoint : ChatCheckpoint.values()) {
                metrics.policyNotEnforced(policy, checkpoint, PolicyReadFailure.UNAVAILABLE);
            }
        }
        metrics.policyNotEnforced(ChatPolicy.KICK, ChatCheckpoint.ENTRY, PolicyReadFailure.STORE_CORRUPTED);

        // then: 하나라도 거부됐으면 미터 수가 모자란다
        String scrape = registry.scrape();
        assertThat(registry.find("chat.policy.open").counters())
                .as("등록이 조용히 거부되면 개수가 줄어든다").hasSize(7);
        assertThat(scrape).contains("policy=\"room_ended\"", "policy=\"kick\"", "policy=\"ban\"");
        assertThat(scrape).contains("checkpoint=\"entry\"", "checkpoint=\"send\"");
        assertThat(scrape).contains("failure=\"unavailable\"", "failure=\"store_corrupted\"");
    }

    @Test
    @DisplayName("나머지 다섯 지표의 모든 열거 값이 스크레이프에 나온다")
    void everyEnumValueSurvivesScrape() {
        // when
        for (RateLimitDegradation mode : RateLimitDegradation.values()) {
            metrics.rateLimitDegraded(mode);
        }
        for (DeliveryStage stage : DeliveryStage.values()) {
            metrics.deliveryDropped(stage);
        }
        for (SessionCutReason reason : SessionCutReason.values()) {
            metrics.sessionCut(reason);
        }
        for (PersistStage stage : PersistStage.values()) {
            metrics.persistTimedOut(stage);
        }
        // 태그 없는 하나 — 조합은 없지만 **등록 자체**가 스크레이프에 나오는지는 같은 축이다.
        // 이 열거가 미터와 함께 늘지 않으면 "지표를 늘릴 때 여기에 한 줄" 규율이 깨진 채 초록이다.
        metrics.dictionaryFastPathLost();

        // then
        assertThat(registry.find("chat.ratelimit.degraded").counters())
                .hasSize(RateLimitDegradation.values().length);
        assertThat(registry.find("chat.delivery.dropped").counters())
                .hasSize(DeliveryStage.values().length);
        assertThat(registry.find("chat.session.cut").counters())
                .hasSize(SessionCutReason.values().length);
        assertThat(registry.find("chat.persist.timeout").counters())
                .hasSize(PersistStage.values().length);

        String scrape = registry.scrape();
        assertThat(scrape).contains("chat_ratelimit_degraded_total");
        assertThat(scrape).contains("chat_delivery_dropped_total");
        assertThat(scrape).contains("chat_session_cut_total");
        assertThat(scrape).contains("chat_dictionary_fastpath_lost_total");
        assertThat(scrape).contains("chat_persist_timeout_total");
    }
}
