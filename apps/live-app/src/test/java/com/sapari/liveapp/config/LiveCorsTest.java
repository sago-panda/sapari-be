package com.sapari.liveapp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.HttpServletResponse;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
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
import com.sapari.live.application.port.LiveMetrics;
import com.sapari.liveapp.security.EnterRateLimitFilter;
import com.sapari.liveapp.security.EnterRateLimitProperties;
import com.sapari.liveapp.security.EnterRateLimiter;
import com.sapari.liveapp.security.StatelessJwtAuthenticationFilter;
import com.sapari.liveapp.security.TokenRevocationFilter;

/** 실제 liveFilterChain 위에서 CORS 를 본다 — 사전 요청이 인증보다 앞에서 응답되는지가 핵심이다. */
class LiveCorsTest {

    private static final String FRONT = "https://front.example.com";
    private static final String PRIVATE_PATH = "/api/v1/lives/rooms/room-id/ingress";

    @Test
    void preflightFromAllowedOriginPassesWithoutAuth() throws Exception {
        withMvc(FRONT, mvc -> {
            mvc.perform(options(PRIVATE_PATH)
                            .header("Origin", FRONT)
                            .header("Access-Control-Request-Method", "PUT")
                            .header("Access-Control-Request-Headers", "authorization,content-type"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", FRONT))
                    .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
            // 실제 요청은 인증 판정을 그대로 받되, 응답에 CORS 헤더가 붙어 브라우저가 401 을 읽는다.
            mvc.perform(get(PRIVATE_PATH).header("Origin", FRONT))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("Access-Control-Allow-Origin", FRONT))
                    .andExpect(header().string("Access-Control-Expose-Headers", "Retry-After"));
        });
    }

    @Test
    void otherOriginIsRejected() throws Exception {
        withMvc(FRONT, mvc -> mvc.perform(options(PRIVATE_PATH)
                        .header("Origin", "https://evil.example.com")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin")));
    }

    @Test
    // 상태 코드는 단정하지 않는다 — CORS 빈이 없던 dev 와 같은 200 이고(실측), 허용 헤더가 없으니 브라우저가 막는다.
    void emptyOriginsLeaveCorsOff() throws Exception {
        withMvc("", mvc -> mvc.perform(options(PRIVATE_PATH)
                        .header("Origin", FRONT)
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin")));
    }

    @Test
    void bindsCsvAndTrims() {
        assertThat(bind(FRONT + ", http://localhost:3000").allowedOrigins())
                .containsExactly(FRONT, "http://localhost:3000");
        assertThat(bind("").allowedOrigins()).isEmpty();
    }

    @Test
    void malformedOriginFailsFast() {
        for (String bad : List.of(FRONT + "/", FRONT + "/app", "https://*.example.com", "front.example.com",
                "https://front.example.com:443", "http://localhost:80", "http://localhost:70000")) {
            assertThatThrownBy(() -> new CorsProperties(List.of(bad))).as(bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static CorsProperties bind(String csv) {
        return new Binder(new MapConfigurationPropertySource(Map.of("cors.allowed-origins", csv)))
                .bindOrCreate("cors", CorsProperties.class);
    }

    private interface MvcCheck {
        void run(MockMvc mvc) throws Exception;
    }

    private static void withMvc(String csv, MvcCheck check) throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            // 빈 이름이 corsConfigurationSource 여야 .cors() 가 집는다 — 운영과 같은 이름으로 넣는다.
            context.addBeanFactoryPostProcessor(beanFactory -> beanFactory.registerSingleton(
                    "corsConfigurationSource", new LiveCorsConfig().corsConfigurationSource(bind(csv))));
            context.register(TestConfig.class);
            context.refresh();
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(context.getBean(FilterChainProxy.class)).build();
            check.run(mvc);
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
            EnterRateLimitFilter enterRateLimitFilter = new EnterRateLimitFilter(
                    new EnterRateLimiter(new EnterRateLimitProperties(
                            1_000_000, 1_000_000, 1_000_000, 1_000_000, 1, null, null, null),
                            LiveMetrics.NOOP));
            TokenRevocationFilter tokenRevocationFilter = new TokenRevocationFilter(
                    mock(AccessTokenRevocationChecker.class), mock(SessionRevocationChecker.class), LiveMetrics.NOOP);
            return new LiveSecurityConfig().liveFilterChain(http,
                    new StatelessJwtAuthenticationFilter(mock(JwtTokenProvider.class)),
                    enterRateLimitFilter, tokenRevocationFilter, entryPoint, mock(JwtAccessDeniedHandler.class));
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
