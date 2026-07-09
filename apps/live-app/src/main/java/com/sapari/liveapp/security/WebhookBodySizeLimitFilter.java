package com.sapari.liveapp.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * webhook 경로의 요청 본문 크기를 <b>읽기 전에</b> Content-Length로 제한한다.
 *
 * <p>{@code /webhooks/**}는 미인증(permitAll) 공개 경로라, 서명 검증 전에 대용량 본문을 힙에 전량
 * 버퍼링하면 메모리 DoS가 된다. 컨트롤러가 body를 읽기 전(필터 단계)에서 Content-Length가 상한을 넘으면
 * 413으로 즉시 거부해 버퍼링 자체를 막는다. verifier의 크기 검사는 chunked(Content-Length 부재) 대비 2차 방어.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WebhookBodySizeLimitFilter extends OncePerRequestFilter {

    private static final String WEBHOOK_PREFIX = "/webhooks/";
    private static final long MAX_BODY_BYTES = 64 * 1024;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (request.getRequestURI().startsWith(WEBHOOK_PREFIX)
                && request.getContentLengthLong() > MAX_BODY_BYTES) {
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            return;
        }
        filterChain.doFilter(request, response);
    }
}
