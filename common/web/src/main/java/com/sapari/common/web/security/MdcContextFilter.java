package com.sapari.common.web.security;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청 단위 로깅 컨텍스트를 MDC에 주입한다.
 * - requestId: 한 요청의 모든 로그 라인을 상관(correlation)하기 위한 식별자
 * - userId: 인증 이후 {@code JwtAuthenticationFilter}가 채운다
 *
 * 결정된 requestId는 응답 헤더(X-Request-Id)로도 노출한다.
 * 시큐리티 필터 체인보다 먼저 실행되어 전체 요청을 감싸고, finally에서 MDC를 정리한다.
 */
public class MdcContextFilter extends OncePerRequestFilter {

    private static final String REQUEST_ID = "requestId";
    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String requestId = resolveRequestId(request);
        MDC.put(REQUEST_ID, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId); // 클라가 장애 신고 시 로그 추적에 쓰도록 응답에 노출

        try {
            filterChain.doFilter(request, response);
        } finally {
            // 스레드 풀 재사용 시 다음 요청으로의 컨텍스트 누수 방지 (requestId + userId 모두 정리)
            MDC.clear();
        }
    }

    private String resolveRequestId(HttpServletRequest request) {
        String header = request.getHeader(REQUEST_ID_HEADER);
        if (header == null || header.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return header;
    }
}
