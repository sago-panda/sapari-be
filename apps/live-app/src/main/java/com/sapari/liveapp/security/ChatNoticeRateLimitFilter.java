package com.sapari.liveapp.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 공개 현재 공지 조회에 상한을 거는 자리. 서는 자리(시큐리티 체인 앞)와 본문 없는 429 는 형제
 * {@link ChatHistoryRateLimitFilter} 와 같고 이유도 같다.
 */
@RequiredArgsConstructor
public class ChatNoticeRateLimitFilter extends OncePerRequestFilter {

    private final ChatNoticeRateLimiter rateLimiter;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!ChatNoticePaths.isNoticeRead(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        if (rateLimiter.tryRead()) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(rateLimiter.retryAfterSeconds()));
    }
}
