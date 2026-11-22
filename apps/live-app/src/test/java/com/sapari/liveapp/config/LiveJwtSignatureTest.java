package com.sapari.liveapp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import tools.jackson.databind.json.JsonMapper;

import com.sapari.common.securityjwt.jwt.JwtProperties;
import com.sapari.common.securityjwt.jwt.JwtSubject;
import com.sapari.common.securityjwt.jwt.JwtTestKeys;
import com.sapari.common.securityjwt.jwt.JwtTokenProvider;
import com.sapari.common.securityjwt.store.AccessTokenRevocationChecker;
import com.sapari.common.securityjwt.store.SessionRevocationChecker;
import com.sapari.common.web.security.JwtAccessDeniedHandler;
import com.sapari.common.web.security.JwtAuthenticationEntryPoint;
import com.sapari.global.time.TimeProvider;
import com.sapari.live.application.port.LiveMetrics;
import com.sapari.live.application.port.RoomTokenClaims;
import com.sapari.live.infrastructure.config.RoomTokenProperties;
import com.sapari.live.infrastructure.media.RoomTokenProvider;
import com.sapari.liveapp.security.ChatReadRateLimitFixtures;
import com.sapari.liveapp.security.CredentialIssueRateLimitFixtures;
import com.sapari.liveapp.security.EnterRateLimitFilter;
import com.sapari.liveapp.security.EnterRateLimitProperties;
import com.sapari.liveapp.security.EnterRateLimiter;
import com.sapari.liveapp.security.StatelessJwtAuthenticationFilter;
import com.sapari.liveapp.security.TokenRevocationFilter;

/**
 * live-app 은 <b>공개키만으로</b> 사용자 토큰을 검증한다(SPR-210).
 *
 * <p>운영 체인({@link LiveSecurityConfig#liveFilterChain})에 검증 전용 {@link JwtTokenProvider} 를 꽂고,
 * api-app 이 개인키로 발급한 RS256 토큰은 통과하고 HMAC 시절 토큰은 401 인지 본다.
 */
class LiveJwtSignatureTest {

    private static final String ISSUER = "sapari-test";
    private static final String AUTHENTICATED_URI = "/api/v1/lives/recent";

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @RestController
    static class Probe {
        @GetMapping(AUTHENTICATED_URI)
        String recent() {
            return "OK";
        }
    }

    @Test
    @DisplayName("api-app 이 개인키로 서명한 RS256 access 토큰은 통과한다")
    void rs256TokenSignedByApiAppIsAccepted() throws Exception {
        JwtTokenProvider apiApp = new JwtTokenProvider(
                JwtTestKeys.properties(ISSUER, 900L, 1_209_600L), new TimeProvider(Clock.systemUTC()));
        String token = apiApp.createAccessToken(new JwtSubject(
                UUID.randomUUID(), UUID.randomUUID(), "USER", "닉", "a@b.c"));

        MvcResult result = requestWith(token);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).isEqualTo("OK");
    }

    @Test
    @DisplayName("HMAC 공유 비밀로 서명한 이전 방식 토큰은 401 이다")
    void hmacTokenIsRejected() throws Exception {
        String token = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer(ISSUER)
                .subject(UUID.randomUUID().toString())
                .claim("sid", UUID.randomUUID().toString())
                .claim("role", "ADMIN")
                .claim("tokenType", "ACCESS")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 600_000))
                .signWith(Keys.hmacShaKeyFor(
                        "old-shared-hmac-secret-old-shared-hmac-secret".getBytes(StandardCharsets.UTF_8)))
                .compact();

        MvcResult result = requestWith(token);

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    /**
     * 룸 토큰과 사용자 토큰은 둘 다 RS256 이다. 가장 나쁜 경우(같은 키쌍을 썼다 — {@link LiveJwtKeyGuardConfig}
     * 가 기동 시 막는 설정)에도 룸 토큰이 사용자 토큰 자리에 통하지 않는 것은 kid 헤더 부재·iss·tokenType 때문이다.
     */
    @Test
    @DisplayName("룸 토큰은 사용자 토큰 키쌍으로 서명돼 있어도 사용자 토큰 자리에서 401 이다")
    void roomTokenIsRejectedAsUserToken() throws Exception {
        RoomTokenProvider roomTokens = new RoomTokenProvider(
                new RoomTokenProperties("live", "chat", "unused", 90L),
                JwtTestKeys.keyPair().getPrivate(),
                new TimeProvider(Clock.systemUTC()));
        String roomToken = roomTokens.issue(new RoomTokenClaims(
                UUID.randomUUID(), UUID.randomUUID(), "SELLER", true, "닉", "a@b.c"));

        MvcResult result = requestWith(roomToken);

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    private static MvcResult requestWith(String token) throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(ChainConfig.class);
            context.refresh();
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(context.getBean(FilterChainProxy.class)).build();
            return mvc.perform(get(AUTHENTICATED_URI).header("Authorization", "Bearer " + token))
                    .andReturn();
        }
    }

    /** live-app 설정과 같이 서명키 없이 공개키만 가진 provider. */
    private static JwtTokenProvider liveAppProvider() {
        JwtProperties properties = new JwtProperties(
                ISSUER,
                null,
                List.of(new JwtProperties.VerificationKey(JwtTestKeys.KID, JwtTestKeys.publicKeyBase64())),
                900L,
                1_209_600L);
        return new JwtTokenProvider(properties, new TimeProvider(Clock.systemUTC()));
    }

    private static final class NotRevoked implements AccessTokenRevocationChecker, SessionRevocationChecker {
        @Override
        public boolean isRevoked(UUID id) {
            return false;
        }
    }

    @Configuration
    @EnableWebSecurity
    @EnableWebMvc
    static class ChainConfig {
        @Bean
        Probe probe() {
            return new Probe();
        }

        @Bean
        SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
            NotRevoked checker = new NotRevoked();
            EnterRateLimitFilter rateLimit = new EnterRateLimitFilter(new EnterRateLimiter(
                    new EnterRateLimitProperties(1_000_000, 1_000_000, 1_000_000, 1_000_000, 1, null, null, null),
                    LiveMetrics.NOOP));
            return new LiveSecurityConfig().liveFilterChain(http,
                    new StatelessJwtAuthenticationFilter(liveAppProvider()),
                    rateLimit,
                    ChatReadRateLimitFixtures.unlimited(),
                    CredentialIssueRateLimitFixtures.unlimited(),
                    new TokenRevocationFilter(checker, checker, LiveMetrics.NOOP),
                    new JwtAuthenticationEntryPoint(JsonMapper.builder().build(), new TimeProvider(Clock.systemUTC())),
                    mock(JwtAccessDeniedHandler.class));
        }
    }
}
