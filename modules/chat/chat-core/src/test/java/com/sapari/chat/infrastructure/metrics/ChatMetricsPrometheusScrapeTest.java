package com.sapari.chat.infrastructure.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

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

    /**
     * 손열거의 분모를 닫는다 — <b>권위 집합을 코드가 훑는다.</b>
     *
     * <p>이 파일의 머리 javadoc 이 적는 규율이 *"지표를 늘릴 때 여기에 한 줄씩 늘릴 것"* 이고
     * {@code modules/chat/AGENTS.md} 가 그 결함을 이미 진단했다 — 손열거는 마지막으로 기억한
     * 사람만큼만 완전하다. SPR-177 은 <b>빠진 줄을 더했고 손열거 자체는 남겼다.</b>
     *
     * <p>⚠️ <b>이 칸은 처음부터 초록이다</b> — 그래서 초록인 것이 근거가 될 수 없다. 근거는
     * 역변이다: {@code ChatMeterNames} 에 7번째 상수를 더하고 테스트를 한 줄도 안 건드렸을 때
     * <b>이 칸이 없으면</b> 두 계기 클래스 6칸이 전부 초록이고(실측: SPR-179 설계,
     * {@code GRADLE_EXIT=0}), <b>이 칸이 있으면</b> 빨강이다. 분모가 0 에서 1 로 간다.
     *
     * <p>태그 축은 이미 구조적이다(위 두 칸이 {@code values()} 를 돈다). 비어 있던 것은 이름 축뿐이다.
     */
    @Test
    @DisplayName("선언된 미터 이름 전수가 스크레이프에 나온다 — 7번째를 더하면 빨강")
    void everyDeclaredMeterNameSurvivesScrape() throws Exception {
        // given: 권위 집합 = ChatMeterNames 의 "chat." 으로 시작하는 String 상수.
        // 태그 키(policy·checkpoint…)는 접두가 달라 값으로 갈린다 — 필드명 규칙(TAG_)에 기대지 않는다.
        List<String> declared = declaredMeterNames();
        assertThat(declared)
                .as("권위 집합이 비면 이 칸은 아무것도 보증하지 않는다 — 분모가 0 이 된다")
                .isNotEmpty();

        // when: ChatMetrics 의 모든 메서드를 한 번씩 호출한다. 열거 인자는 첫 값으로 채운다 —
        // 조합은 위 두 칸이 보고, 여기서는 "그 이름의 미터가 등록되는가" 만 본다.
        for (Method method : ChatMetrics.class.getDeclaredMethods()) {
            if (method.isSynthetic() || method.isBridge()) {
                continue;
            }
            Object[] args = new Object[method.getParameterCount()];
            Class<?>[] types = method.getParameterTypes();
            for (int i = 0; i < args.length; i++) {
                assertThat(types[i].isEnum())
                        .as("열거가 아닌 인자가 생겼다 — 이 칸을 넓혀야 한다: %s", method.getName())
                        .isTrue();
                args[i] = types[i].getEnumConstants()[0];
            }
            method.invoke(metrics, args);
        }

        // then: 선언된 이름마다 미터가 실재하고, <b>실제 스크레이프 텍스트</b>에 나온다.
        // 등록만 보면 이 클래스의 축(스크레이프)을 안 보는 것이고, 리플렉션은 상수를 따라가므로
        // 이름 변경도 못 잡는다 — 그래서 변환된 이름을 스크레이프에서 찾는다(점 → 밑줄).
        String scrape = registry.scrape();
        for (String name : declared) {
            assertThat(registry.find(name).meters())
                    .as("선언만 있고 등록이 없다 — 또는 이 칸을 안 늘렸다: %s", name)
                    .isNotEmpty();
            assertThat(scrape)
                    .as("등록은 됐는데 스크레이프에 안 나온다(태그 키 집합 충돌이 그 모양이다): %s", name)
                    .contains(name.replace('.', '_'));
        }
    }

    /** {@code ChatMeterNames} 는 패키지 전용이고 이 테스트가 같은 패키지라 리플렉션으로 읽힌다. */
    private static List<String> declaredMeterNames() throws Exception {
        List<String> names = new ArrayList<>();
        for (Field field : ChatMeterNames.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) {
                continue;
            }
            String value = (String) field.get(null);
            if (value != null && value.startsWith("chat.")) {
                names.add(value);
            }
        }
        return names;
    }
}
