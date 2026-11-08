package com.sapari.chat.infrastructure.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

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
        metrics.persistTimedOut(PersistStage.WRITE);

        // then
        assertThat(registry.get("chat.ratelimit.degraded").tags("mode", "local_window")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("chat.delivery.dropped").tags("stage", "session_emit_contention")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("chat.session.cut").tags("reason", "lifetime")
                .counter().count()).isEqualTo(1);
    }

    /**
     * 레지스트리가 던져도 어댑터는 던지지 않는다 — <b>포트 전수를 코드가 센다.</b>
     *
     * <p>⚠️ 전에는 여섯 메서드를 손으로 열거하고 주석이 "여섯 메서드 전부" 라고 적었다. 포트에
     * 일곱 번째가 생기자 그 수가 거짓이 되고 새 메서드의 분모가 0 이 됐다(SPR-179 에서 실제로
     * 그랬다 — {@code subscriptionResubscribed}). 그래서 열거를 리플렉션으로 바꾼다.
     *
     * <p>이 계약이 왜 하중인가: 이 포트의 호출부 대부분이 {@code onErrorResume} 람다, 즉 fail-open
     * 분기다. 거기서 던지면 <b>통과시키기로 한 요청이 거부된다</b>. 새 호출 자리는
     * {@code doBeforeRetry} 람다인데 거기서 던지면 자가복구 루프가 그 자리에서 끝난다.
     */
    @Test
    @DisplayName("⭐ 레지스트리가 던져도 어댑터는 던지지 않는다 — 포트 메서드 전수를 리플렉션으로 센다")
    void neverThrows() {
        // given: 모든 counter() 호출이 던지는 레지스트리
        ChatMetrics broken = new MicrometerChatMetrics(new SimpleMeterRegistry() {
            @Override
            public Counter counter(String name, String... tags) {
                throw new IllegalStateException("registry 고장");
            }
        });

        // when & then: 포트의 모든 메서드. 열거를 손으로 적지 않으므로 메서드가 늘면 자동으로 덮인다.
        assertThat(ChatMetrics.class.getDeclaredMethods())
                .as("포트에 메서드가 없다 — 리플렉션이 빈 집합을 돌면 이 칸은 아무것도 보증하지 않는다")
                .isNotEmpty();
        assertThatCode(() -> invokeEveryMethod(broken, false)).doesNotThrowAnyException();
    }

    /**
     * null 열거도 던지지 않는다 — 전에는 {@code policyNotEnforced} 하나만 봤다.
     *
     * <p>{@code tag()} 가 null 을 {@code "unknown"} 으로 접는 것이 그 계약이고, 그 접기를 빠뜨린
     * 메서드가 하나라도 있으면 그 자리에서 NPE 가 난다 — fail-open 람다 안이라 전송이 거부로 뒤집힌다.
     * 그래서 여기서도 포트 전수를 돈다.
     */
    @Test
    @DisplayName("⭐ null 열거도 던지지 않는다 — NPE 는 fail-open 람다에서 전송을 거부로 뒤집는다")
    void nullTagsDoNotThrow() {
        // when & then: 모든 메서드를 null 인자로
        assertThatCode(() -> invokeEveryMethod(metrics, true)).doesNotThrowAnyException();

        // 그리고 접힌 값이 실제로 "unknown" 으로 나간다
        assertThat(registry.get("chat.policy.open")
                .tags("policy", "unknown", "checkpoint", "unknown", "failure", "unknown")
                .counter().count()).isEqualTo(1);
    }

    /**
     * 포트의 모든 메서드를 한 번씩 부른다. {@code nulls} 면 열거 인자를 전부 null 로 넘긴다.
     *
     * <p>{@code Method.invoke} 가 대상의 예외를 {@code InvocationTargetException} 으로 감싸므로
     * 원인을 벗겨 다시 던진다 — 안 벗기면 "던지지 않는다" 단언이 <b>감싼 예외</b>를 보고 깨져서
     * 실패 이유가 계약 위반인지 리플렉션 사정인지 구분되지 않는다.
     */
    private static void invokeEveryMethod(ChatMetrics target, boolean nulls) throws Throwable {
        for (Method method : ChatMetrics.class.getDeclaredMethods()) {
            if (method.isSynthetic() || method.isBridge()) {
                continue;
            }
            Object[] args = new Object[method.getParameterCount()];
            Class<?>[] types = method.getParameterTypes();
            for (int i = 0; i < args.length; i++) {
                assertThat(types[i].isEnum())
                        .as("열거가 아닌 인자가 생겼다 — 이 헬퍼를 넓혀야 한다: %s", method.getName())
                        .isTrue();
                args[i] = nulls ? null : types[i].getEnumConstants()[0];
            }
            try {
                method.invoke(target, args);
            } catch (InvocationTargetException wrapped) {
                throw wrapped.getCause();
            }
        }
    }
}
