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
 * webhook 경로의 <b>Content-Length가 있는</b> 요청을 읽기 전에 빠르게 거부하는 fast-fail 필터.
 *
 * <p>{@code /webhooks/**}는 미인증(permitAll) 공개 경로라 대용량 본문 버퍼링이 메모리 DoS가 된다.
 * 이 필터는 Content-Length 헤더가 상한을 넘으면 body를 읽기 전에 413으로 즉시 끊는다.
 *
 * <p>경로 판정은 {@link WebhookPaths} 에 맡긴다 — 여기서 {@code getRequestURI()} 로 직접 비교하면
 * 인코딩·컨텍스트 패스 때문에 라우팅과 어긋나 필터가 조용히 꺼진다(그 사유는 그쪽 javadoc 참고).
 *
 * <p><b>주의:</b> {@code Transfer-Encoding: chunked} 요청은 Content-Length가 없어(-1) 이 필터를 통과한다.
 * 그런 요청의 실제 방어는 컨트롤러의 상한 스트리밍 읽기(bounded read)가 담당한다. 즉 이 필터는 방어의
 * 전부가 아니라 "정직한 Content-Length 요청을 더 일찍 끊는" 보조 수단이다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WebhookBodySizeLimitFilter extends OncePerRequestFilter {

    private static final long MAX_BODY_BYTES = 64 * 1024;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (WebhookPaths.isWebhookRequest(request)
                && request.getContentLengthLong() > MAX_BODY_BYTES) {
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            return;
        }
        filterChain.doFilter(request, response);
    }
}
