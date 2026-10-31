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
import com.sapari.common.web.security.JwtAccessDeniedHandler;
import com.sapari.common.web.security.JwtAuthenticationEntryPoint;
import com.sapari.liveapp.security.StatelessJwtAuthenticationFilter;

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
            return new LiveSecurityConfig().liveFilterChain(http,
                    new StatelessJwtAuthenticationFilter(mock(JwtTokenProvider.class)), entryPoint,
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
