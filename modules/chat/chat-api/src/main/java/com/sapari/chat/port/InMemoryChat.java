package com.sapari.chat.port;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * I/O 없이 메모리 안에서 끝나는 동기 chat 타입(지표 기록, 욕설 필터) — 두 앱 모두 호출해도 된다.
 *
 * <p>{@link BlockingChat} 과 시그니처로는 구별되지 않는다. 그래서 이 표시는 선언하는 쪽의 주장이고,
 * 저장소나 외부 호출을 감싼 타입에 달면 {@code ArchitectureTest} 가 그 거짓을 잡지 못한다.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface InMemoryChat {
}
