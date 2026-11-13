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
 * 공개 채팅 이력 조회에 상한을 거는 자리.
 *
 * <p><b>형제 {@link EnterRateLimitFilter} 와 서는 자리가 다르다.</b> 그쪽은 인증 여부로 버킷을
 * 가르므로 {@code SecurityContextHolder} 가 채워진 뒤여야 하고, 그래서 시큐리티 체인 <b>안</b>에
 * JWT 필터 뒤에 선다. 이쪽은 전역 버킷 하나라 <b>신원을 보지 않는다</b> — 그러면 체인 <b>앞</b>에
 * 두는 쪽이 낫다: 거부되는 요청이 JWT 파싱 비용조차 쓰지 않는다. 폭주를 막는 것이 목적이므로
 * 그 차이가 그대로 목적에 닿는다.
 *
 * <p><b>본문을 싣지 않는다.</b> 형제와 같은 선택이고 이유도 같다 — 던지면 공용
 * {@code GlobalExceptionHandler} 가 4xx 를 거부마다 {@code log.warn} 으로 남겨 로그 I/O 가 폭주
 * 규모에 비례한다. 대가도 같다: 이 429 는 {@code error.code} 가 없어 그 필드를 읽는 파이프라인에
 * 빈칸이다. 상태코드와 {@code Retry-After} 가 전부다.
 */
@RequiredArgsConstructor
public class ChatHistoryRateLimitFilter extends OncePerRequestFilter {

    private final ChatHistoryRateLimiter rateLimiter;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!ChatHistoryPaths.isHistoryRead(request)) {
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
