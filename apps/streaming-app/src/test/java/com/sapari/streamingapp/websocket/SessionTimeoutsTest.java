package com.sapari.streamingapp.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 상한 셋의 불변식. 셋을 한 값으로 묶은 이유가 이 검증이라, 검증을 지우면 묶은 의미가 사라진다.
 */
@DisplayName("세션 상한 — 서로 제약한다")
class SessionTimeoutsTest {

    private static final Duration LIFETIME = Duration.ofMinutes(60);
    private static final Duration INTERVAL = Duration.ofSeconds(30);
    private static final Duration DEADLINE = Duration.ofSeconds(90);

    @Test
    @DisplayName("기본 조합은 받아들인다")
    void defaults_are_accepted() {
        assertThatCode(() -> new SessionTimeouts(LIFETIME, INTERVAL, DEADLINE)).doesNotThrowAnyException();
        assertThat(new SessionTimeouts(LIFETIME, INTERVAL, DEADLINE).pingInterval()).isEqualTo(INTERVAL);
    }

    /**
     * ⭐ 0은 두 관계 검사를 통과하지만 치명적이다.
     *
     * <p>{@code Flux.interval(ZERO, ZERO)}는 예외 없이 <b>최대 속도로 틱한다</b>(리뷰어 실측
     * ≈17M tick/s) — 프로퍼티 한 줄로 연결마다 ping 폭주가 된다. 음수는 더 나쁘다: 생성은
     * 통과하고 <b>구독 시점에</b> 깨지므로 기동은 성공하고 모든 WS 핸드셰이크가 죽는다.
     */
    @Test
    @DisplayName("⭐ ping 간격이 0이면 거부한다 — 통과하면 연결마다 최대 속도로 ping 이 나간다")
    void zero_ping_interval_is_rejected() {
        assertThatThrownBy(() -> new SessionTimeouts(LIFETIME, Duration.ZERO, DEADLINE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("양수");
    }

    @Test
    @DisplayName("⭐ 음수는 거부한다 — 통과하면 기동은 되고 모든 접속이 깨진다(기동 실패보다 나쁘다)")
    void negative_values_are_rejected() {
        assertThatThrownBy(() -> new SessionTimeouts(LIFETIME, Duration.ofSeconds(-1), DEADLINE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SessionTimeouts(LIFETIME, INTERVAL, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SessionTimeouts(Duration.ofSeconds(-1), INTERVAL, DEADLINE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * ⭐ 기한이 간격의 두 배 미만이면 거부한다.
     *
     * <p>"한 번보다 크면 된다"로는 부족하다 — 생존 시계가 등록 시각으로 초기화되고 판정이
     * {@code now - last >= deadline}이라, {@code deadline = interval + δ}에서는 유실이 없어도
     * 왕복 지연이 δ를 넘기는 것만으로 첫 점검에서 멀쩡한 세션이 끊긴다.
     */
    @Test
    @DisplayName("⭐ 생존 기한이 ping 간격의 두 배 미만이면 거부한다 — 유실 없이도 멀쩡한 세션이 끊긴다")
    void deadline_shorter_than_twice_the_interval_is_rejected() {
        assertThatThrownBy(() -> new SessionTimeouts(LIFETIME, INTERVAL, INTERVAL.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("두 배");
        // 정확히 두 배는 받아들인다
        assertThatCode(() -> new SessionTimeouts(LIFETIME, INTERVAL, INTERVAL.multipliedBy(2)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("⭐ 수명이 생존 기한보다 짧으면 거부한다 — 생존 확인이 한 번도 돌지 않는다")
    void lifetime_shorter_than_the_deadline_is_rejected() {
        assertThatThrownBy(() -> new SessionTimeouts(DEADLINE, INTERVAL, DEADLINE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("수명");
    }

    @Test
    @DisplayName("null 은 거부한다")
    void nulls_are_rejected() {
        assertThatThrownBy(() -> new SessionTimeouts(null, INTERVAL, DEADLINE))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
