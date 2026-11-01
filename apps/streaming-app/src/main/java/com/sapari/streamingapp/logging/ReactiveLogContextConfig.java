package com.sapari.streamingapp.logging;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.sapari.global.logging.LogFields;

import io.micrometer.context.ContextRegistry;
import reactor.core.publisher.Hooks;

/**
 * 리액티브 상관 식별자 배선.
 *
 * <p>두 가지를 켠다 — MDC 키별 접근자 등록과 Reactor 의 자동 컨텍스트 전파. 둘 중 하나만 하면
 * <b>아무 에러 없이 식별자만 안 붙는다</b>: 접근자가 없으면 전파할 대상이 없고, 훅이 없으면
 * 접근자를 아무도 부르지 않는다. 그 조용한 반쪽 상태를 테스트가 막는다.
 *
 * <p>훅은 프로세스 전역이고 되돌릴 자리가 없다. 이 앱이 리액티브 전용이라 그게 문제가 아니지만,
 * 대가는 있다 — 신호마다 ThreadLocal 을 쓰고 지우는 비용이 붙는다. 그 대가를 받아들이는 근거는
 * 이 앱의 로그가 지금 <b>어느 세션·어느 방인지 모른 채</b> 쌓이고 있다는 것이다(실측: 로그 17건 중
 * 7건에 식별자가 없었다).
 */
@Configuration
public class ReactiveLogContextConfig {

    /** 상관 축으로 쓰는 키들. 늘리면 그 키도 Reactor Context 에 써야 붙는다. */
    static final List<String> PROPAGATED_KEYS =
            List.of(LogFields.REQUEST_ID, LogFields.SESSION_ID, LogFields.USER_ID, LogFields.ROOM_ID);

    /**
     * 표식 빈 — 등록을 부팅에 태운다.
     *
     * <p>{@code @PostConstruct} 가 아니라 {@code @Bean} 몸통에서 하는 이유는 싱글톤 사전 초기화가
     * 포트 bind 보다 앞이라는 것을 이 앱에서 이미 쟀기 때문이다. 첫 WS 핸드셰이크가 들어올 때
     * 훅이 이미 켜져 있어야 한다.
     */
    @Bean
    public ReactiveLogContextRegistration reactiveLogContext() {
        ContextRegistry registry = ContextRegistry.getInstance();
        PROPAGATED_KEYS.forEach(key -> registry.registerThreadLocalAccessor(new MdcThreadLocalAccessor(key)));
        Hooks.enableAutomaticContextPropagation();
        return new ReactiveLogContextRegistration();
    }

    /** 동작은 없다. 존재가 "배선됐다" 는 사실이다. */
    public static final class ReactiveLogContextRegistration {
    }
}
