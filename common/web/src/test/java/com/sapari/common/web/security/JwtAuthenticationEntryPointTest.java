package com.sapari.common.web.security;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.ServletException;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;

import com.sapari.global.time.TimeProvider;

@DisplayName("JWT 인증 실패 핸들러 테스트")
class JwtAuthenticationEntryPointTest {

    @Test
    @DisplayName("인증 실패 시 401 JSON 응답을 반환한다")
    void commenceReturnsUnauthorizedJsonResponse() throws ServletException, IOException {
        // given
        JwtAuthenticationEntryPoint entryPoint = new JwtAuthenticationEntryPoint(new ObjectMapper(), timeProvider());
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        // when
        entryPoint.commence(
                request,
                response,
                new InsufficientAuthenticationException("required authentication")
        );

        // then
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).contains(MediaType.APPLICATION_JSON_VALUE);
        assertThat(response.getCharacterEncoding()).isEqualTo("UTF-8");
        assertThat(response.getContentAsString()).contains("\"status\":401");
        assertThat(response.getContentAsString()).contains("인증이 필요합니다.");
    }

    private TimeProvider timeProvider() {
        return new TimeProvider(Clock.fixed(Instant.now(), ZoneOffset.UTC));
    }
}
