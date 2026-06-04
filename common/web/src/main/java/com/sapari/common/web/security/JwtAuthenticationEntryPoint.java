package com.sapari.common.web.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import com.sapari.common.core.exception.CommonErrorCode;
import com.sapari.common.web.response.ErrorResponse;
import com.sapari.global.time.TimeProvider;

@Slf4j(topic = "JWT_AUTHENTICATION_ENTRY_POINT")
@RequiredArgsConstructor
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final String REQUEST_ID = "requestId";

    private final ObjectMapper objectMapper;
    private final TimeProvider timeProvider;

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException
    ) throws IOException, ServletException {
        log.warn("Unauthenticated request: {}", authException.getMessage());

        ErrorResponse errorResponse = ErrorResponse.of(
                CommonErrorCode.UNAUTHORIZED,
                MDC.get(REQUEST_ID),
                timeProvider.now()
        );

        writeErrorResponse(response, HttpStatus.UNAUTHORIZED, errorResponse);
    }

    private void writeErrorResponse(
            HttpServletResponse response,
            HttpStatus status,
            ErrorResponse errorResponse
    ) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
    }
}
