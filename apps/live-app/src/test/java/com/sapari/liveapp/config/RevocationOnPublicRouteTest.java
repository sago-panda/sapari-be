package com.sapari.liveapp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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

import com.sapari.common.securityjwt.jwt.JwtTokenClaims;
import com.sapari.common.securityjwt.jwt.JwtTokenProvider;
import com.sapari.common.securityjwt.jwt.JwtTokenType;
import com.sapari.common.securityjwt.store.AccessTokenRevocationChecker;
import com.sapari.common.securityjwt.store.SessionRevocationChecker;
import com.sapari.common.web.security.JwtAccessDeniedHandler;
import com.sapari.common.web.security.JwtAuthenticationEntryPoint;
import com.sapari.live.application.port.LiveMetrics;
import com.sapari.liveapp.security.EnterRateLimitFilter;
import com.sapari.liveapp.security.EnterRateLimitProperties;
import com.sapari.liveapp.security.EnterRateLimiter;
import com.sapari.liveapp.security.LiveUserPrincipal;
import com.sapari.liveapp.security.StatelessJwtAuthenticationFilter;
import com.sapari.liveapp.security.TokenRevocationFilter;

/**
 * 폐기 조회의 결과가 <b>공개(permitAll) 입장 경로에서 무엇으로 보이는지</b>를 고정한다.
 *
 * <p><b>여기가 분모의 빈칸이었다.</b> {@code TokenRevocationFilterTest} 는 인증이 거둬지는 것까지
 * 재는데, 이 필터가 지키려는 경로({@code GET /api/v1/lives/rooms/*})는 {@code permitAll} 이라
 * <b>인증이 거둬져도 요청이 통과한다</b> — 뒤에서 {@code AnonymousAuthenticationFilter} 가 익명을
 * 채우고, {@code @AuthenticationPrincipal LiveUserPrincipal} 은 타입이 안 맞아 {@code null} 이
 * 되고({@code errorOnInvalidType} 기본값이 false), {@code LiveController} 가 그 {@code null} 을
 * 게스트 분기 기준으로 쓴다. 즉 "거둬졌다" 와 "거부됐다" 가 이 경로에서 같지 않다.
 *
 * <p>두 칸이 그 차이를 각각 고정한다. 폐기 <b>확정</b>은 게스트로 내려가는 것이 맞고(폐기된
 * 사용자는 헤더를 떼면 어차피 낯선 사람과 같은 것을 받는다 — 권한 획득 0), 조회 <b>실패</b>는
 * 그렇지 않다(방주인이 자기 방 권한을 조용히 잃는다).
 */
class RevocationOnPublicRouteTest {

    private static final String ENTER_URI = "/api/v1/lives/rooms/" + UUID.randomUUID();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** 컨트롤러가 principal 을 어떻게 받았는지 그대로 돌려준다 — {@code LiveController} 의 분기 기준과 같다. */
    @RestController
    static class Probe {
        @GetMapping("/api/v1/lives/rooms/{roomId}")
        String enter(@AuthenticationPrincipal LiveUserPrincipal principal) {
            return principal == null ? "GUEST" : "MEMBER:" + principal.role();
        }
    }

    private static final class StubChecker
            implements AccessTokenRevocationChecker, SessionRevocationChecker {
        private final boolean revoked;
        private final boolean throwing;

        StubChecker(boolean revoked, boolean throwing) {
            this.revoked = revoked;
            this.throwing = throwing;
        }

        @Override
        public boolean isRevoked(UUID id) {
            if (throwing) {
                throw new IllegalStateException("Redis down");
            }
            return revoked;
        }
    }

    /**
     * 판매자 access 토큰을 파싱하는 것처럼 꾸민다 — 이 축에서 잃는 것이 {@code owner} 라 역할을
     * SELLER 로 둔다.
     *
     * <p>⚠️ <b>{@code SecurityContextHolder} 에 직접 심으면 이 테스트는 아무것도 재지 않는다.</b>
     * 체인의 {@code SecurityContextHolderFilter} 가 저장소에서 읽은 컨텍스트로 홀더를 덮으므로
     * 심어 둔 신원이 폐기 조회에 닿기 전에 사라지고, 그러면 결과가 "강등됐다" 가 아니라 "애초에
     * 신원이 없었다" 가 된다 — 두 경우의 응답이 같아서 구분되지 않는다(실제로 그렇게 한 번
     * 틀렸다). 그래서 운영과 같은 길로 간다: Bearer 헤더 → {@link StatelessJwtAuthenticationFilter}
     * 가 체인 <b>안에서</b> principal 을 만든다.
     */
    private static JwtTokenProvider sellerTokenProvider() {
        JwtTokenProvider provider = mock(JwtTokenProvider.class);
        when(provider.parseToken(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new JwtTokenClaims(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        "SELLER", JwtTokenType.ACCESS, "닉", "a@b.c",
                        Instant.now().plusSeconds(600)));
        return provider;
    }

    /**
     * 이 칸의 체커를 설정에 넘기는 통로. {@code AnnotationConfigWebApplicationContext} 는
     * {@code registerBean(Class, Supplier)} 를 갖지 않고({@code GenericApplicationContext} 쪽이다)
     * {@code getBeanFactory()} 는 refresh 전에 던진다 — 그래서 정적 자리를 쓴다. 테스트가
     * 순차 실행이고 두 칸이 각자 컨텍스트를 세우므로 섞이지 않는다.
     */
    private static StubChecker checkerForThisCase;

    private static MvcResult enterWith(StubChecker token) throws Exception {
        checkerForThisCase = token;
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(ChainConfig.class);
            context.refresh();
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(context.getBean(FilterChainProxy.class)).build();
            return mvc.perform(get(ENTER_URI).header("Authorization", "Bearer seller-token"))
                    .andReturn();
        }
    }

    /**
     * ⭐ <b>조회 실패는 공개 입장 경로에서도 보인다 — 조용히 게스트로 내려가지 않는다.</b>
     *
     * <p>한때 이 경로가 <b>200 + GUEST</b> 였다 — 아래 확정-폐기 칸이 그 길(거둬진 컨텍스트 →
     * 200 + GUEST)을 고정하고, 조회 실패를 "거두기" 로 되돌리는 역변이가 이 칸을 깨뜨린다.
     * 그러면 저장소 장애 중에 방주인이 자기
     * 라이브에 들어가 {@code role=GUEST · owner=false} 룸 토큰을 받고, 채팅 발언권과 특권 뷰를
     * 잃으면서 응답은 200 이고 {@code error.code} 도 없다 — 401 보다 나쁜 모양이다(401 은 적어도
     * 보인다). 그 결함이 "인증이 거둬지는 것" 만 재는 분모에서는 보이지 않았다.
     */
    @Test
    @DisplayName("⭐ 조회 실패 → 503. 한때 200 + GUEST 였고, 그때 방주인이 자기 방 owner 를 잃었다")
    void aFailedLookupIsVisibleEvenOnAPublicRoute() throws Exception {
        MvcResult result = enterWith(new StubChecker(false, true));

        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat(result.getResponse().getContentAsString())
                .as("컨트롤러에 닿으면 게스트 룸 토큰이 발급된다")
                .isEmpty();
    }

    /**
     * ⭐ <b>폐기 확정은 반대로 게스트로 내려가는 것이 맞다.</b>
     *
     * <p>폐기된 사용자가 Authorization 헤더를 떼면 어차피 낯선 사람과 <b>정확히 같은 것</b>을
     * 받는다({@code role=GUEST · owner=false · nickname/email 부재}). 권한 획득이 0 이므로 이
     * 경로를 막을 이유가 없다 — 로그아웃한 사람의 비회원 시청을 끊는 것이 된다.
     *
     * <p>이 칸을 명시로 두는 이유: 위 칸을 고치면서 "실패도 확정도 다 끊자" 로 넓히면 그게 바로
     * 그 손실이고, 그 변경이 조용히 통과하지 못하게 한다.
     */
    @Test
    @DisplayName("⭐ 폐기 확정 → 200 + GUEST. 헤더를 뗀 요청과 같은 것을 주는 것이 의도다")
    void aConfirmedRevocationDowngradesToGuestOnAPublicRoute() throws Exception {
        MvcResult result = enterWith(new StubChecker(true, false));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).isEqualTo("GUEST");
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
            StubChecker checker = checkerForThisCase;
            // 상한은 이 테스트의 축이 아니다 — 한도를 넉넉히 둬서 경로 판정을 가리지 않게 한다.
            EnterRateLimitFilter rateLimit = new EnterRateLimitFilter(new EnterRateLimiter(
                    new EnterRateLimitProperties(1_000_000, 1_000_000, 1_000_000, 1_000_000, 1),
                    LiveMetrics.NOOP));
            return new LiveSecurityConfig().liveFilterChain(http,
                    new StatelessJwtAuthenticationFilter(sellerTokenProvider()),
                    rateLimit,
                    new TokenRevocationFilter(checker, checker, LiveMetrics.NOOP),
                    mock(JwtAuthenticationEntryPoint.class),
                    mock(JwtAccessDeniedHandler.class));
        }
    }
}
