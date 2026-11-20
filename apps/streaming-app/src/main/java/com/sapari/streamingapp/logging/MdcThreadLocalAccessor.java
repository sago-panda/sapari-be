package com.sapari.streamingapp.logging;

import org.slf4j.MDC;

import io.micrometer.context.ThreadLocalAccessor;

/**
 * Reactor Context 의 한 키를 MDC 의 같은 키에 비춘다.
 *
 * <p>이게 없으면 리액티브 코드의 로그에는 상관 식별자를 붙일 수 없다. MDC 는 {@code ThreadLocal}
 * 이고 Reactor 는 신호마다 스레드를 바꿀 수 있어서, 실측으로 <b>{@code publishOn} 뒤와
 * {@code onErrorResume} 안에서 값이 {@code null}</b> 이었다. 하필 이 저장소의 열화 로그
 * (fail-open·강등·전달 포기)가 거의 전부 그 {@code onErrorResume} 람다 안이다 — 즉 <b>가장 알고
 * 싶은 줄이 가장 확실하게 식별자를 잃는</b> 구조였다.
 *
 * <p>키를 Reactor Context 와 MDC 에서 <b>같은 문자열</b>로 쓴다({@code LogFields}). 자동 전파가
 * 그 이름으로 맞춰 꺼내기 때문이고, 그래서 이름이 흩어지면 조용히 비는 것이 아니라 아예 안 붙는다.
 */
public final class MdcThreadLocalAccessor implements ThreadLocalAccessor<String> {

    private final String key;

    public MdcThreadLocalAccessor(String key) {
        this.key = key;
    }

    @Override
    public Object key() {
        return key;
    }

    @Override
    public String getValue() {
        return MDC.get(key);
    }

    @Override
    public void setValue(String value) {
        MDC.put(key, value);
    }

    /**
     * 값을 비운다.
     *
     * <p>지우지 않고 두면 스레드가 재사용될 때 <b>남의 요청 식별자가 붙은 로그</b>가 나온다 —
     * 비어 있는 것보다 나쁘다. 비면 "모른다" 지만, 남의 값이 붙으면 조사가 엉뚱한 요청으로 간다.
     */
    @Override
    public void setValue() {
        MDC.remove(key);
    }
}
