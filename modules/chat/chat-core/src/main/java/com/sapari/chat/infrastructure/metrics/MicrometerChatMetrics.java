package com.sapari.chat.infrastructure.metrics;

import com.sapari.chat.application.port.ChatCheckpoint;
import com.sapari.chat.application.port.ChatMetrics;
import com.sapari.chat.application.port.ChatPolicy;
import com.sapari.chat.application.port.DeliveryStage;
import com.sapari.chat.application.port.PolicyReadFailure;
import com.sapari.chat.application.port.RateLimitDegradation;
import com.sapari.chat.application.port.SessionCutReason;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link ChatMetrics} micrometer 구현.
 *
 * <p>{@code @Component} 를 달지 않는다 — 생성은 {@code ChatMetricsConfig} 가 {@code MeterRegistry}
 * 유무를 보고 한다. 스테레오타입을 달면 registry 없는 컨텍스트에서 주입이 실패한다.
 */
@Slf4j
public class MicrometerChatMetrics implements ChatMetrics {

    private final MeterRegistry registry;

    public MicrometerChatMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void policyNotEnforced(ChatPolicy policy, ChatCheckpoint checkpoint, PolicyReadFailure failure) {
        safe(() -> registry.counter(ChatMeterNames.POLICY_OPEN,
                ChatMeterNames.TAG_POLICY, tag(policy),
                ChatMeterNames.TAG_CHECKPOINT, tag(checkpoint),
                ChatMeterNames.TAG_FAILURE, tag(failure)).increment());
    }

    @Override
    public void rateLimitDegraded(RateLimitDegradation mode) {
        safe(() -> registry.counter(ChatMeterNames.RATE_LIMIT_DEGRADED,
                ChatMeterNames.TAG_MODE, tag(mode)).increment());
    }

    @Override
    public void deliveryDropped(DeliveryStage stage) {
        safe(() -> registry.counter(ChatMeterNames.DELIVERY_DROPPED,
                ChatMeterNames.TAG_STAGE, tag(stage)).increment());
    }

    @Override
    public void sessionCut(SessionCutReason reason) {
        safe(() -> registry.counter(ChatMeterNames.SESSION_CUT,
                ChatMeterNames.TAG_REASON, tag(reason)).increment());
    }

    @Override
    public void dictionaryFastPathLost() {
        safe(() -> registry.counter(ChatMeterNames.DICTIONARY_FAST_PATH_LOST).increment());
    }

    @Override
    public void persistTimedOut() {
        safe(() -> registry.counter(ChatMeterNames.PERSIST_TIMEOUT).increment());
    }

    /**
     * 열거 이름을 태그 값으로. null 도 받는다 — 호출부가 fail-open 람다 안이라 거기서 NPE 가
     * 나면 통과시키기로 한 요청이 거부된다. 값을 잃는 것이 그보다 훨씬 싸다.
     */
    private static String tag(Enum<?> value) {
        return value == null ? "unknown" : value.name().toLowerCase();
    }

    /**
     * 계측은 부수효과이지 업무가 아니다 — <b>던지면 업무가 뒤집힌다.</b>
     *
     * <p>이 포트의 호출부 대부분이 {@code onErrorResume} 람다, 즉 fail-open 분기 안이다. 거기서
     * 예외가 나가면 Reactor 가 하류로 전파해 <b>통과시키기로 한 요청이 거부된다</b> — 가용성 우선
     * 정책이 관측 때문에 정반대가 된다. {@code SendChatService} 가 그 불변식을 주석으로 경고해 둔
     * 자리에 실제로 호출을 넣는 것이 이 커밋이라, 여기서 막는다.
     *
     * <p>조용히 넘기지는 않는다 — 지표가 비는데 원인을 모르는 상태가 되면 안 된다.
     */
    private void safe(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("chat 지표 기록 실패 — 업무 처리에는 영향 없음", e);
        }
    }
}
