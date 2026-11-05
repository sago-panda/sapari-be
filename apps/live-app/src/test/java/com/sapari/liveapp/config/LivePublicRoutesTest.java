package com.sapari.liveapp.config;

import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import com.sapari.common.securityjwt.jwt.JwtTokenProvider;
import com.sapari.common.securityjwt.store.AccessTokenRevocationChecker;
import com.sapari.common.securityjwt.store.SessionRevocationChecker;
import com.sapari.common.web.security.JwtAccessDeniedHandler;
import com.sapari.common.web.security.JwtAuthenticationEntryPoint;
import com.sapari.liveapp.security.EnterRateLimitFilter;
import com.sapari.liveapp.security.EnterRateLimitProperties;
import com.sapari.liveapp.security.EnterRateLimiter;
import com.sapari.liveapp.security.StatelessJwtAuthenticationFilter;
import com.sapari.liveapp.security.TokenRevocationFilter;

class LivePublicRoutesTest {
    @Test
    void onlyExplicitViewerGetsAreAnonymous() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(TestConfig.class);
            context.refresh();
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(context.getBean(FilterChainProxy.class)).build();
            mvc.perform(get("/api/v1/lives/rooms")).andExpect(status().isOk());
            mvc.perform(get("/api/v1/lives/rooms/room-id")).andExpect(status().isOk());
            mvc.perform(get("/api/v1/lives/rooms/room-id/replay")).andExpect(status().isOk());
            mvc.perform(get("/api/v1/lives/rooms/room-id/ingress")).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/v1/lives/future-private")).andExpect(status().isUnauthorized());
            mvc.perform(post("/api/v1/lives/rooms")).andExpect(status().isUnauthorized());
        }
    }

    @Configuration
    @EnableWebSecurity
    @EnableWebMvc
    static class TestConfig {
        @Bean
        SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
            JwtAuthenticationEntryPoint entryPoint = mock(JwtAuthenticationEntryPoint.class);
            doAnswer(invocation -> {
                invocation.getArgument(1, HttpServletResponse.class).setStatus(401);
                return null;
            }).when(entryPoint).commence(any(), any(), any());
            // 입장 상한은 이 테스트의 축이 아니다 — 공개 경로 판정만 본다. 한도를 넉넉히
            // 둬서 상한이 경로 판정 결과를 가리지 않게 한다.
            EnterRateLimitFilter enterRateLimitFilter = new EnterRateLimitFilter(
                    new EnterRateLimiter(new EnterRateLimitProperties(
                            1_000_000, 1_000_000, 1_000_000, 1_000_000, 1),
                            com.sapari.live.application.port.LiveMetrics.NOOP));
            // 폐기 조회도 이 테스트의 축이 아니다. 이 테스트의 요청은 전부 토큰이 없어
            // principal 이 null 이므로 체커는 호출되지 않는다 — 목을 두는 것은 체인을
            // 세우기 위한 것이고, 호출이 생기면 Mockito 기본값(false=폐기 아님)이라
            // 경로 판정을 가리지 않는다.
            TokenRevocationFilter tokenRevocationFilter = new TokenRevocationFilter(
                    mock(AccessTokenRevocationChecker.class), mock(SessionRevocationChecker.class),
                    com.sapari.live.application.port.LiveMetrics.NOOP);
            return new LiveSecurityConfig().liveFilterChain(http,
                    new StatelessJwtAuthenticationFilter(mock(JwtTokenProvider.class)),
                    enterRateLimitFilter, tokenRevocationFilter, entryPoint,
                    mock(JwtAccessDeniedHandler.class));
        }

        @Bean
        Routes routes() {
            return new Routes();
        }
    }

    @RestController
    static class Routes {
        @RequestMapping("/api/v1/lives/**")
        String route() {
            return "ok";
        }
    }
}
