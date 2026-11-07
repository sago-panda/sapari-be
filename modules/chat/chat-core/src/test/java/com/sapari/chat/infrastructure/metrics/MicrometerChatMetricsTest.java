package com.sapari.chat.infrastructure.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.chat.application.port.ChatCheckpoint;
import com.sapari.chat.application.port.ChatMetrics;
import com.sapari.chat.application.port.ChatPolicy;
import com.sapari.chat.application.port.DeliveryStage;
import com.sapari.chat.application.port.PolicyReadFailure;
import com.sapari.chat.application.port.RateLimitDegradation;
import com.sapari.chat.application.port.SessionCutReason;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** 어댑터 — 이름·태그가 맞는지, 그리고 <b>무슨 일이 있어도 던지지 않는지</b>. */
@DisplayName("MicrometerChatMetrics")
class MicrometerChatMetricsTest {

    private MeterRegistry registry;
    private ChatMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new MicrometerChatMetrics(registry);
    }

    @Test
    @DisplayName("판정 통과는 policy·checkpoint·failure 세 태그로 나간다")
    void policyOpenCarriesThreeTags() {
        // when
        metrics.policyNotEnforced(ChatPolicy.ROOM_ENDED, ChatCheckpoint.ENTRY, PolicyReadFailure.UNAVAILABLE);

        // then
        assertThat(registry.get("chat.policy.open")
                .tags("policy", "room_ended", "checkpoint", "entry", "failure", "unavailable")
                .counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("강등·전달포기·세션절단이 각자의 지표로 나간다")
    void eachSignalHasItsOwnMeter() {
        // when
        metrics.rateLimitDegraded(RateLimitDegradation.LOCAL_WINDOW);
        metrics.deliveryDropped(DeliveryStage.SESSION_EMIT_CONTENTION);
        metrics.sessionCut(SessionCutReason.LIFETIME);
        // 태그가 없는 둘도 같이 센다 — 손열거라 미터를 더할 때 여기가 같이 늘지 않으면 그 미터의
        // safe() 누락이 아무 칸에도 안 걸린다(실측: persistTimedOut 이 그 상태로 들어왔다).
        metrics.dictionaryFastPathLost();
        metrics.persistTimedOut();

        // then
        assertThat(registry.get("chat.ratelimit.degraded").tags("mode", "local_window")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("chat.delivery.dropped").tags("stage", "session_emit_contention")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("chat.session.cut").tags("reason", "lifetime")
                .counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("⭐ 레지스트리가 던져도 어댑터는 던지지 않는다 — 호출부가 fail-open 람다 안이다")
    void neverThrows() {
        // given: 모든 counter() 호출이 던지는 레지스트리
        ChatMetrics broken = new MicrometerChatMetrics(new SimpleMeterRegistry() {
            @Override
            public Counter counter(String name, String... tags) {
                throw new IllegalStateException("registry 고장");
            }
        });

        // when & then: 네 메서드 전부
        assertThatCode(() -> {
            broken.policyNotEnforced(ChatPolicy.KICK, ChatCheckpoint.SEND, PolicyReadFailure.UNAVAILABLE);
            broken.rateLimitDegraded(RateLimitDegradation.TTL_FALLBACK);
            broken.deliveryDropped(DeliveryStage.REDIS_PUBLISH);
            broken.sessionCut(SessionCutReason.BUFFER_OVERFLOW);
            broken.dictionaryFastPathLost();
            broken.persistTimedOut();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("⭐ null 태그도 던지지 않는다 — NPE 는 fail-open 람다에서 전송을 거부로 뒤집는다")
    void nullTagsDoNotThrow() {
        // when & then
        assertThatCode(() -> metrics.policyNotEnforced(null, null, null)).doesNotThrowAnyException();
        assertThat(registry.get("chat.policy.open")
                .tags("policy", "unknown", "checkpoint", "unknown", "failure", "unknown")
                .counter().count()).isEqualTo(1);
    }
}
