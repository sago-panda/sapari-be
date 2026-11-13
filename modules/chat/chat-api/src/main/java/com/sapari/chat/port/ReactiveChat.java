package com.sapari.chat.port;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * {@code Mono}·{@code Flux} 로 답하는 chat 타입 — MVC 앱(live-app)은 이 타입과 그 구현체를 호출하면 안 된다.
 * 결국 {@code .block()} 을 강요당하기 때문이다. {@code ArchitectureTest} 가 이 표시로 판정한다.
 *
 * <p>chat-api 의 포트는 이것과 {@link BlockingChat} 중 하나를 반드시 단다(같은 테스트가 센다).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ReactiveChat {
}
