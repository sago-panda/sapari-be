package com.sapari.liveapp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import com.sapari.common.securityjwt.jwt.JwtTokenProvider;
import com.sapari.common.securityjwt.store.AccessTokenRevocationChecker;
import com.sapari.common.securityjwt.store.SessionRevocationChecker;
import com.sapari.common.web.security.JwtAccessDeniedHandler;
import com.sapari.common.web.security.JwtAuthenticationEntryPoint;
import com.sapari.live.application.port.LiveMetrics;
import com.sapari.liveapp.security.EnterRateLimitFilter;
import com.sapari.liveapp.security.EnterRateLimitProperties;
import com.sapari.liveapp.security.EnterRateLimiter;
import com.sapari.liveapp.security.StatelessJwtAuthenticationFilter;
import com.sapari.liveapp.security.TokenRevocationFilter;

/**
 * 필터 셋의 순서 — 설정에서 확인한다.
 *
 * <p>세 필터의 순서는 취향이 아니라 두 제약이 함께 정한 값이다. {@code TokenRevocationFilterTest}
 * 가 그 제약의 <b>결과</b>(거부분이 조회를 치지 않는다)를 재고, 이 칸이 체인에 박힌 <b>순서
 * 자체</b>를 잰다 — 둘 중 하나만 있으면 다른 쪽이 조용히 뒤집힌다.
 */
class LiveFilterOrderTest {

    /**
     * ⭐ <b>인증 → 상한 → 폐기 조회, 그리고 셋 다 인가 판정보다 앞.</b>
     *
     * <ul>
     *   <li>인증이 상한보다 앞이어야 상한이 게스트·회원 버킷을 가를 수 있다
     *       ({@code SecurityContextHolder} 가 채워진 뒤여야 한다).</li>
     *   <li>상한이 폐기 조회보다 앞이어야 거부될 입장이 Redis 왕복을 치지 않는다.</li>
     *   <li>폐기 조회가 {@code AuthorizationFilter} 보다 앞이어야 역할 규칙
     *       (SELLER·ADMIN)이 <b>거둬진</b> 컨텍스트를 본다. 뒤로 가면 폐기된 ADMIN 토큰이
     *       밴 해제를 통과한 다음에 인증이 거둬진다 — 즉 아무것도 막지 못한다.</li>
     * </ul>
     */
    @Test
    @DisplayName("⭐ 인증 → 입장 상한 → 폐기 조회 → 인가 판정 순서로 박힌다")
    void theChainOrdersAuthenticationThenRateLimitThenRevocation() {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(TestConfig.class);
            context.refresh();

            List<Filter> filters = context.getBean(FilterChainProxy.class)
                    .getFilterChains().getFirst().getFilters();
            List<String> names = filters.stream()
                    .map(filter -> filter.getClass().getSimpleName())
                    .toList();

            int authentication = names.indexOf(StatelessJwtAuthenticationFilter.class.getSimpleName());
            int rateLimit = names.indexOf(EnterRateLimitFilter.class.getSimpleName());
            int revocation = names.indexOf(TokenRevocationFilter.class.getSimpleName());
            int authorization = names.indexOf(AuthorizationFilter.class.getSimpleName());

            assertThat(List.of(authentication, rateLimit, revocation, authorization))
                    .as("넷 다 체인에 있어야 아래 비교가 뜻을 갖는다 — 실제 순서: %s", names)
                    .doesNotContain(-1);
            assertThat(authentication)
                    .as("상한이 인증보다 앞이면 회원 요청이 게스트 버킷을 쓴다")
                    .isLessThan(rateLimit);
            assertThat(rateLimit)
                    .as("폐기 조회가 상한보다 앞이면 거부될 요청까지 Redis 왕복을 친다")
                    .isLessThan(revocation);
            assertThat(revocation)
                    .as("인가가 폐기 조회보다 앞이면 폐기된 ADMIN 토큰이 밴 해제를 통과한다")
                    .isLessThan(authorization);
        }
    }

    @Configuration
    @EnableWebSecurity
    @EnableWebMvc
    static class TestConfig {
        @Bean
        SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
            EnterRateLimitFilter enterRateLimitFilter = new EnterRateLimitFilter(
                    new EnterRateLimiter(
                            new EnterRateLimitProperties(1_000_000, 1_000_000, 1_000_000, 1_000_000, 1),
                            LiveMetrics.NOOP));
            TokenRevocationFilter tokenRevocationFilter = new TokenRevocationFilter(
                    mock(AccessTokenRevocationChecker.class), mock(SessionRevocationChecker.class),
                    LiveMetrics.NOOP);
            return new LiveSecurityConfig().liveFilterChain(http,
                    new StatelessJwtAuthenticationFilter(mock(JwtTokenProvider.class)),
                    enterRateLimitFilter, tokenRevocationFilter,
                    mock(JwtAuthenticationEntryPoint.class),
                    mock(JwtAccessDeniedHandler.class));
        }
    }
}
