package com.sapari.live.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.sapari.global.time.TimeProvider;
import com.sapari.live.application.port.RoomTokenClaims;
import com.sapari.live.infrastructure.media.RoomTokenProvider;

/**
 * 서명 캐시 설정의 <b>기동 가드</b>를 잰다 — 어긴 값이 조용히 올려 맞춰지지 않고 멈추는지.
 *
 * <p>{@link #theCachedTokenLengthIsMeasuredNotGuessed()} 는 가드가 아니라 <b>근거의 분모</b>다.
 * {@code RoomTokenCacheProperties} 가 슬롯 상한을 "토큰 문자열이 무게의 전부" 라는 이유로 두는데,
 * 그 길이를 안 재면 상한의 근거가 추측이 된다.
 */
class RoomTokenCachePropertiesTest {

    private static RoomTokenCacheProperties cache(Integer slots, Long ttlSeconds) {
        return new RoomTokenCacheProperties(true, slots, ttlSeconds);
    }

    @Test
    @DisplayName("값이 없으면 기본값을 스스로 채운다 — yml 필수 키를 만들지 않는다")
    void theDefaultsAreAppliedWhenAbsent() {
        RoomTokenCacheProperties defaults = new RoomTokenCacheProperties(null, null, null);
        assertThat(defaults.enabled()).isTrue();
        assertThat(defaults.slots()).isEqualTo(16_384);
        assertThat(defaults.ttlSeconds()).isEqualTo(30L);
        // 기본값끼리는 서로 어기지 않아야 한다 — 기본 발급 TTL 90s 의 절반 이하.
        assertThatCode(() -> defaults.validateAgainst(issuance(90L))).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "slots={0} 는 거부")
    @ValueSource(ints = {3, 1_000, 12, 16_383, 24})
    @DisplayName("2의 거듭제곱이 아니면 올려 맞추지 않고 거부한다")
    void theNonPowerOfTwoSlotsRefuseToBoot(int slots) {
        assertThatThrownBy(() -> cache(slots, 30L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(RoomTokenCacheProperties.SLOTS_MUST_BE_POWER_OF_TWO);
    }

    @ParameterizedTest(name = "slots={0} 는 허용")
    @ValueSource(ints = {1, 2, 16, 16_384, 1 << 16})
    @DisplayName("2의 거듭제곱은 경계값까지 허용한다 — 상한 자신도 거듭제곱이다")
    void thePowerOfTwoSlotsAreAccepted(int slots) {
        assertThat(cache(slots, 30L).slots()).isEqualTo(slots);
    }

    @ParameterizedTest(name = "slots={0}")
    @ValueSource(ints = {0, -1, (1 << 16) + 1, 1 << 20})
    @DisplayName("범위를 벗어난 슬롯 수는 거부한다 — 상한 초과는 거듭제곱 검사보다 먼저 걸린다")
    void theOutOfRangeSlotsRefuseToBoot(int slots) {
        assertThatThrownBy(() -> cache(slots, 30L)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "ttl={0}s")
    @ValueSource(longs = {0L, -5L})
    @DisplayName("TTL 은 양수여야 한다")
    void theNonPositiveTtlRefusesToBoot(long ttl) {
        assertThatThrownBy(() -> cache(16, ttl)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("캐시 TTL 이 발급 TTL 의 절반을 넘으면 거부한다 — 적중의 잔여 수명 하한이 깨진다")
    void theCacheTtlMustLeaveHalfTheIssuedLife() {
        // 발급 90s 에 캐시 46s → 최악 잔여 44s 로 절반 아래.
        assertThatThrownBy(() -> cache(16, 46L).validateAgainst(issuance(90L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(RoomTokenCacheProperties.TTL_MUST_LEAVE_HALF_THE_LIFE);
        // 정확히 절반은 통과한다(경계).
        assertThatCode(() -> cache(16, 45L).validateAgainst(issuance(90L))).doesNotThrowAnyException();
        // 발급 TTL 이 짧아지면 같은 캐시 값이 어긴다 — 두 레코드에 걸친 불변식이라는 증거.
        assertThatThrownBy(() -> cache(16, 30L).validateAgainst(issuance(59L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 캐시 한 슬롯이 들고 있는 토큰의 <b>실제 길이</b>를 잰다. 회원 claim(닉네임·이메일 포함)이
     * 가장 긴 모양이다 — 게스트는 그 둘이 빠지고 캐시를 타지도 않는다.
     */
    @Test
    @DisplayName("슬롯이 드는 토큰 길이는 실측값이다 — 슬롯 상한의 근거가 추측이 되지 않게")
    void theCachedTokenLengthIsMeasuredNotGuessed() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        RoomTokenProperties props = new RoomTokenProperties(
                "live", "chat", Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()), 90L);
        TimeProvider time = new TimeProvider(Clock.fixed(Instant.parse("2026-11-06T00:00:00Z"), ZoneOffset.UTC));

        String token = new RoomTokenProvider(props, kp.getPrivate(), time).issue(
                new RoomTokenClaims(UUID.randomUUID(), UUID.randomUUID(), "SELLER", true,
                        "열두자닉네임입니다", "someone.with.a.long.address@example.com"));

        // 이 길이가 슬롯 상한의 근거다 — RoomTokenCacheProperties.MAX_SLOTS javadoc 이 인용한다.
        assertThat(token.length()).isBetween(500, 900);
        long worstCaseMb = token.length() * (long) (1 << 16) / (1024 * 1024);
        System.out.println("### 캐시 슬롯이 드는 토큰 길이 = " + token.length()
                + "자 (RS256·회원 claim 전량) → 슬롯 상한 65,536 에서 문자열만 약 " + worstCaseMb + "MB");
        // 상한이 만드는 최악이 세 자리 MB 로 가지 않는 것이 상한을 1<<16 으로 내린 이유다.
        assertThat(worstCaseMb).isLessThan(100L);
    }

    private static RoomTokenProperties issuance(long expirationSeconds) {
        return new RoomTokenProperties("live", "chat", "dGVzdA==", expirationSeconds);
    }
}
