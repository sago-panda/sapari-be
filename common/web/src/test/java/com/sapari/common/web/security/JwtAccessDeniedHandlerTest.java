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
import org.springframework.security.access.AccessDeniedException;

import com.sapari.global.time.TimeProvider;

@DisplayName("JWT 인가 실패 핸들러 테스트")
class JwtAccessDeniedHandlerTest {

    @Test
    @DisplayName("권한 부족 시 403 JSON 응답을 반환한다")
    void handleReturnsForbiddenJsonResponse() throws ServletException, IOException {
        // given
        JwtAccessDeniedHandler accessDeniedHandler = new JwtAccessDeniedHandler(new ObjectMapper(), timeProvider());
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        // when
        accessDeniedHandler.handle(
                request,
                response,
                new AccessDeniedException("access denied")
        );

        // then
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).contains(MediaType.APPLICATION_JSON_VALUE);
        assertThat(response.getCharacterEncoding()).isEqualTo("UTF-8");
        assertThat(response.getContentAsString()).contains("\"status\":403");
        assertThat(response.getContentAsString()).contains("접근 권한이 없습니다.");
    }

    private TimeProvider timeProvider() {
        return new TimeProvider(Clock.fixed(Instant.now(), ZoneOffset.UTC));
    }
}
