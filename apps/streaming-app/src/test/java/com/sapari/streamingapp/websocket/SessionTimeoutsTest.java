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
     * ≈17M tick/s) — 프로퍼티 한 줄로 연결마다 ping 폭주가 된다.
     */
    @Test
    @DisplayName("⭐ ping 간격이 0이면 거부한다 — 통과하면 연결마다 최대 속도로 ping 이 나간다")
    void zero_ping_interval_is_rejected() {
        assertThatThrownBy(() -> new SessionTimeouts(LIFETIME, Duration.ZERO, DEADLINE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("양수");
    }

    /**
     * ⭐ 음수는 셋 다 거부한다. 실패 모양은 값마다 다르다(실측).
     *
     * <p>{@code pingInterval} 은 {@code interval(-1s, -1s)} 이 구독 시점에
     * {@code IllegalArgumentException("period >= 0 required")} 로 깨져 기동은 성공하고 모든 WS
     * 핸드셰이크가 죽는다. {@code maxLifetime} 은 {@code Mono.delay(-1s)} 가 예외 없이 즉시 발화해
     * 모든 세션이 등록 직후 끊긴다. {@code livenessDeadline} 은 {@code interval(delay=-1s, …)} 이
     * 예외 없이 첫 틱을 내고 {@code isUnresponsive} 가 항상 참이라 모든 세션이 즉시 수거된다.
     *
     * <p><b>메시지까지 본다.</b> 타입만 보면 양수 가드가 아니라 뒤의 관계 검사가 던져도 만족된다 —
     * 실측으로 양수 검사를 {@code pingInterval} 하나만 남기는 뮤턴트가 이 클래스를 통째로 통과했다
     * (deadline 음수는 "두 배" 검사가, lifetime 음수는 "수명" 검사가 먼저 던진다).
     */
    @Test
    @DisplayName("⭐ 음수는 거부한다 — 통과하면 기동은 되고 모든 접속이 깨진다(기동 실패보다 나쁘다)")
    void negative_values_are_rejected() {
        assertThatThrownBy(() -> new SessionTimeouts(LIFETIME, Duration.ofSeconds(-1), DEADLINE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("양수");
        assertThatThrownBy(() -> new SessionTimeouts(LIFETIME, INTERVAL, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("양수");
        assertThatThrownBy(() -> new SessionTimeouts(Duration.ofSeconds(-1), INTERVAL, DEADLINE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("양수");
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

    /**
     * ⭐ 가드가 <b>지터와 첫 점검 지연</b>을 함께 본다.
     *
     * <p>{@code (91s, 30s, 90s)} 는 "수명 > 기한" 만 보는 가드를 통과한다. 그런데 수명은 지터로 아래로
     * 흩어지고(최소 77.35s) 첫 점검은 기한이 아니라 기한 + 간격(120s)이라, 그 조합에서는 생존 확인이
     * <b>단 한 번도 돌지 않는다.</b> 가드가 막으려던 "코드는 남고 효과는 없다" 가 그 상태다.
     *
     * <p>지터 폭이 핸들러에만 있으면 이 검사를 쓸 수 없어서 폭을 이 레코드로 옮겼다.
     */
    @Test
    @DisplayName("⭐ 지터를 뺀 최소 수명이 첫 점검보다 짧으면 거부한다 — 생존 확인이 한 번도 돌지 않는다")
    void lifetime_that_the_jitter_pushes_below_the_first_check_is_rejected() {
        // given: 셋 다 양수, 기한은 간격의 세 배, 수명 > 기한 — 옛 가드 전부 통과
        Duration lifetime = Duration.ofSeconds(91);

        // when & then: 지터 적용 최소 수명 77.35s < 첫 점검 120s
        assertThatThrownBy(() -> new SessionTimeouts(lifetime, INTERVAL, DEADLINE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("수명");

        // 기본 조합은 넉넉히 통과한다 — 60m × 0.85 = 51m vs 첫 점검 120s
        assertThatCode(() -> new SessionTimeouts(LIFETIME, INTERVAL, DEADLINE)).doesNotThrowAnyException();
    }

    /**
     * ⭐ 가드의 <b>두 항이 각각</b> 필요하다.
     *
     * <p>위 테스트의 91s 로는 부족하다 — 첫 점검 항만으로 이미 거부돼서, 지터 항을 지우거나 첫 점검
     * 항을 지워도 한쪽이 남아 통과한다. 각 항을 혼자 책임지는 값을 따로 둬야 둘 중 하나를 지우는
     * 변경이 빨간불을 받는다.
     *
     * <p>분리하는 수는 산술로 나온다(기한 90s·간격 30s·지터 15%):
     * <ul>
     *   <li>{@code 120s}: 지터 적용 102s. 기한(90s)보다는 크고 첫 점검(120s)보다는 작다 →
     *       <b>첫 점검 항</b>만이 거부할 수 있다
     *   <li>{@code 130s}: 지터 적용 110.5s 로 거부인데, 지터를 빼면 130s > 120s 로 통과 →
     *       <b>지터 항</b>만이 거부할 수 있다
     * </ul>
     */
    @Test
    @DisplayName("⭐ 가드의 두 항이 각각 필요하다 — 한 항만 지워도 빨간불이 되게")
    void each_term_of_the_lifetime_guard_is_load_bearing() {
        // 첫 점검 항 전용: 102s 는 기한 90s 보다 크고 첫 점검 120s 보다 작다
        assertThatThrownBy(() -> new SessionTimeouts(Duration.ofSeconds(120), INTERVAL, DEADLINE))
                .as("기한만 보는 가드로 되돌리면 통과한다").isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("수명");

        // 지터 항 전용: 지터를 빼면 130s > 120s 로 통과한다
        assertThatThrownBy(() -> new SessionTimeouts(Duration.ofSeconds(130), INTERVAL, DEADLINE))
                .as("지터를 모르는 가드로 되돌리면 통과한다").isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("수명");
    }

    /**
     * ⭐ 경계는 <b>같을 때도 거부</b>다.
     *
     * <p>지터 적용 최소 수명이 첫 점검과 정확히 같으면 생존 확인이 "한 번 돌 수도 있는" 자리가 아니라
     * 돌지 않는 자리다. {@code <=} 를 {@code <} 로 바꾸는 한 글자가 그 경계를 열어 버린다.
     *
     * <p>{@code 141,176,470,589ns} 에서 {@code × 85 / 100} 이 정확히 120초다(정수 나노초 연산).
     */
    @Test
    @DisplayName("⭐ 지터 적용 최소 수명이 첫 점검과 같으면 거부한다 — <= 를 < 로 바꾸는 한 글자를 막는다")
    void the_lifetime_boundary_rejects_equality() {
        assertThatThrownBy(() -> new SessionTimeouts(Duration.ofNanos(141_176_470_589L), INTERVAL, DEADLINE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("수명");
        // 1ns 더 크면 통과한다 — 경계가 그 자리임을 함께 고정한다
        assertThatCode(() -> new SessionTimeouts(Duration.ofNanos(141_176_470_590L), INTERVAL, DEADLINE))
                .doesNotThrowAnyException();
    }

    /**
     * ⭐ 템플릿이 적은 <b>탈출로</b> 트리오가 두 가드를 통과하고 기본값과 같은 내성을 갖는다.
     *
     * <p>수명만 내리는 길이 가드에 막혀 있으므로 템플릿이 탈출로를 하나 적어 둔다. 그 트리오가
     * 가드를 통과하는 것만으로는 부족하다 — 기한/간격 비가 내성을 정하기 때문이다. 판정이
     * {@code now - last >= 기한} 이라 k회 연속 유실이 {@code k × 간격} 을 만들고 그것이 기한보다
     * 작아야 사므로, 비율 3배면 2회, <b>2배면 1회</b>다.
     *
     * <p>{@code (2m, 30s, 60s)} 도 두 가드를 통과하지만 비율이 2배라 내성이 반으로 깎인다. 템플릿이
     * 그걸 "보안을 조이는 길" 로 제시하면 네트워크가 흔들리는 클라이언트를 더 끊는 쪽으로 기운다.
     */
    @Test
    @DisplayName("⭐ 템플릿의 탈출로 트리오는 가드를 통과하고 기본값과 같은 3배 비율을 유지한다")
    void the_documented_escape_trio_keeps_the_default_loss_tolerance() {
        // given: 템플릿이 적은 탈출로
        Duration lifetime = Duration.ofMinutes(2);
        Duration interval = Duration.ofSeconds(20);
        Duration deadline = Duration.ofSeconds(60);

        // when & then: 두 가드를 통과한다
        assertThatCode(() -> new SessionTimeouts(lifetime, interval, deadline)).doesNotThrowAnyException();

        // 그리고 비율이 기본값과 같다 — 가드는 2배까지 허용하므로 이 단언이 따로 필요하다
        assertThat(deadline).as("기한/간격 비가 3배 미만이면 연속 유실 내성이 깎인다")
                .isGreaterThanOrEqualTo(interval.multipliedBy(3));
        assertThat(DEADLINE).as("기본값도 3배다").isGreaterThanOrEqualTo(INTERVAL.multipliedBy(3));
    }

    @Test
    @DisplayName("null 은 거부한다")
    void nulls_are_rejected() {
        assertThatThrownBy(() -> new SessionTimeouts(null, INTERVAL, DEADLINE))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
