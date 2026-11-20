package com.sapari.live.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.sapari.global.time.TimeProvider;
import com.sapari.live.application.port.LiveMetrics;
import com.sapari.live.application.port.RecordingLiveMetrics;
import com.sapari.live.application.port.RoomTokenClaims;
import com.sapari.live.application.port.RoomTokenIssuer;
import com.sapari.live.application.port.RoomTokenSignatureSource;
import com.sapari.live.infrastructure.media.CachingRoomTokenIssuer;
import com.sapari.live.infrastructure.media.RoomTokenProvider;

/**
 * 서명 캐시가 <b>진짜 컨텍스트에서 실제로 쓰이는지</b> 확인한다 — 형제
 * {@code LiveMetricsWiringTest} 가 계측 데코레이터에 대해 하는 것과 같은 축이다.
 *
 * <p><b>왜 별도 테스트인가.</b> {@code RoomTokenConfigTest} 는 {@code @Bean} 메서드를 손으로
 * 불러 반환값을 보고, {@code CachingRoomTokenIssuerTest} 는 delegate 를 목으로 둔다. 둘 다
 * <b>주입 해소</b>를 재지 않는다 — 형제 {@link RoomTokenProvider} 가 {@code @Component} 이고
 * 그것도 {@link RoomTokenIssuer} 이므로 실제 컨텍스트에는 후보가 둘이다. {@code @Primary} 가
 * 이기지 않으면 캐시는 <b>조립되고도 안 쓰인다</b>: 에러 없이 비용만 그대로다.
 *
 * <p>그리고 앞의 두 테스트는 토큰을 {@code "token-1"} 같은 문자열로 둔다. 적중이 돌려주는 것이
 * <b>실제로 검증되는 토큰</b>인지(서명·claim·만료)는 여기서만 잰다 — 캐시가 깨뜨릴 수 있는 것이
 * 바로 그 세 가지다.
 */
class RoomTokenCacheWiringTest {

    private static final Instant NOW = Instant.parse("2026-11-06T00:00:00Z");
    private static final long EXPIRATION_SECONDS = 90L;
    private static final KeyPair KEY_PAIR = generateKeyPair();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RoomTokenConfig.class, Stubs.class)
            .withPropertyValues(
                    "live.room-token.issuer=live",
                    "live.room-token.audience=chat",
                    "live.room-token.expiration-seconds=" + EXPIRATION_SECONDS,
                    "live.room-token.private-key="
                            + Base64.getEncoder().encodeToString(KEY_PAIR.getPrivate().getEncoded()));

    private static RoomTokenClaims memberClaims() {
        return new RoomTokenClaims(UUID.randomUUID(), UUID.randomUUID(), "SELLER", true,
                "판매자닉", "seller@example.com");
    }

    @Test
    @DisplayName("켜져 있으면 주입 해소가 캐시를 고른다 — @Component 형제를 @Primary 가 이긴다")
    void theInjectionResolvesToTheCache() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            // getBean(타입) 이 EnterLiveService 가 거치는 그 해소다.
            assertThat(context.getBean(RoomTokenIssuer.class))
                    .isInstanceOf(CachingRoomTokenIssuer.class);
            // 형제가 컨텍스트에 실재한다는 것도 같이 박는다 — 후보가 하나뿐이면 이 칸이 아무것도
            // 재지 않는다(@Primary 를 지워도 통과한다).
            assertThat(context.getBeansOfType(RoomTokenIssuer.class)).hasSize(2);
        });
    }

    @Test
    @DisplayName("꺼져 있으면 주입 해소가 원본을 고른다")
    void theDisabledCacheResolvesToTheProvider() {
        runner.withPropertyValues("live.room-token.cache.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(RoomTokenIssuer.class))
                    .isSameAs(context.getBean(RoomTokenProvider.class));
        });
    }

    @Test
    @DisplayName("같은 입장 두 번에 개인키 서명은 한 번이고, 적중 토큰이 실제로 검증된다")
    void theCachedTokenIsStillAValidSignedToken() {
        runner.run(context -> {
            RoomTokenIssuer issuer = context.getBean(RoomTokenIssuer.class);
            RoomTokenProvider provider = context.getBean(RoomTokenProvider.class);
            RoomTokenClaims claims = memberClaims();

            String first = issuer.issue(claims);
            String second = issuer.issue(claims);

            assertThat(second).isEqualTo(first);
            // 서명은 한 번만 — 이것이 이 티켓이 사는 이유다.
            verify(provider, times(1)).issue(claims);

            // 적중이 돌려준 것이 실제로 공개키로 검증되고 claim 이 온전한지. 앞의 두 테스트는
            // 토큰을 가짜 문자열로 두므로 이 축을 재지 못한다.
            // ⚠️ 파서에 같은 고정 시계를 준다. 안 주면 파서가 실제 시스템 시계로 exp 를 보고
            // ExpiredJwtException 을 던진다(발급은 2026-11-06T00:00:00Z 고정이므로 항상 과거다).
            // 이 칸이 재려는 것은 "적중 토큰이 유효한 서명·claim 을 들고 있는가" 이고, 만료 판정은
            // 발급 시각 기준으로 봐야 그 축이 측정된다.
            Jws<Claims> parsed = Jwts.parser()
                    .verifyWith(KEY_PAIR.getPublic())
                    .requireIssuer("live")
                    .requireAudience("chat")
                    .clock(() -> java.util.Date.from(NOW))
                    .build()
                    .parseSignedClaims(second);
            Claims body = parsed.getPayload();
            assertThat(body.getSubject()).isEqualTo(claims.userId().toString());
            assertThat(body.get("room")).isEqualTo(claims.roomId().toString());
            assertThat(body.get("role")).isEqualTo("SELLER");
            assertThat(body.get("owner")).isEqualTo(true);
            assertThat(body.get("email")).isEqualTo("seller@example.com");
            assertThat(body.getExpiration().toInstant())
                    .isEqualTo(NOW.plusSeconds(EXPIRATION_SECONDS));

            RecordingLiveMetrics metrics = (RecordingLiveMetrics) context.getBean(LiveMetrics.class);
            assertThat(metrics.roomTokensIssued).containsExactly(
                    RoomTokenSignatureSource.SIGNED, RoomTokenSignatureSource.CACHED);
        });
    }

    @Test
    @DisplayName("게스트는 매번 서명한다 — 같은 컨텍스트에서 회원과 갈리는 것을 본다")
    void theGuestSignsEveryTime() {
        runner.run(context -> {
            RoomTokenIssuer issuer = context.getBean(RoomTokenIssuer.class);
            RoomTokenProvider provider = context.getBean(RoomTokenProvider.class);
            UUID room = UUID.randomUUID();

            // 게스트는 호출마다 userId 가 새로 생기므로 호출자가 claims 를 다시 만드는 모양을 따른다.
            String a = issuer.issue(new RoomTokenClaims(UUID.randomUUID(), room, "GUEST", false, null, null));
            String b = issuer.issue(new RoomTokenClaims(UUID.randomUUID(), room, "GUEST", false, null, null));

            assertThat(a).isNotEqualTo(b);
            verify(provider, times(2)).issue(org.mockito.ArgumentMatchers.any());

            RecordingLiveMetrics metrics = (RecordingLiveMetrics) context.getBean(LiveMetrics.class);
            assertThat(metrics.roomTokensIssued)
                    .as("게스트는 분모에 들어가지 않는다 — 적중률이 구조적으로 눌리지 않게")
                    .isEmpty();
        });
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            return kpg.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Configuration
    static class Stubs {

        @Bean
        TimeProvider timeProvider() {
            return new TimeProvider(Clock.fixed(NOW, ZoneOffset.UTC));
        }

        @Bean
        LiveMetrics liveMetrics() {
            return new RecordingLiveMetrics();
        }

        /** 실제 서명기를 spy 로 감싼다 — 진짜 토큰이 나오면서 호출 수도 센다. */
        @Bean
        RoomTokenProvider roomTokenProvider(RoomTokenProperties properties, PrivateKey key,
                                            TimeProvider timeProvider) {
            return spy(new RoomTokenProvider(properties, key, timeProvider));
        }
    }
}
