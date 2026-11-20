package com.sapari.liveapp.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 공개 채팅 조회(이력 · 현재 공지)에 상한을 거는 자리. 경로마다 자기 리미터를 쓴다.
 *
 * <p><b>시큐리티 체인 안, JWT 필터 뒤에 선다(SPR-203).</b> 게스트 / 회원을 가르려면
 * {@code SecurityContextHolder} 가 채워진 뒤여야 한다. 전에는 신원을 보지 않아 체인 앞에 두고
 * 거부된 요청이 JWT 파싱을 안 하게 했는데, 이제는 토큰이 붙은 요청이 거부돼도 서명 검증을 한 번
 * 치른다(로컬 검증이고 Redis 왕복은 없다 — 비용은 재지 않았다). 토큰이 깨졌으면 JWT 필터가
 * 컨텍스트만 비우고 넘기므로 그 요청은 게스트 버킷에 걸린다.
 *
 * <p>본문 없는 429 와 {@code Retry-After} 만 싣는 것은 형제 {@link EnterRateLimitFilter} 와 같은
 * 선택이고 이유도 같다(던지면 공용 핸들러가 거부마다 WARN 을 쓴다).
 *
 * <p>{@code GET} 과 {@code HEAD} 를 같이 센다 — 본문을 안 보내도 조회 비용은 같다
 * ({@link ChatHistoryPaths} · {@link ChatNoticePaths}).
 */
@RequiredArgsConstructor
public class ChatReadRateLimitFilter extends OncePerRequestFilter {

    private final ChatReadRateLimiter historyLimiter;
    private final ChatReadRateLimiter noticeLimiter;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        ChatReadRateLimiter limiter = limiterFor(request);
        if (limiter == null) {
            filterChain.doFilter(request, response);
            return;
        }
        LiveUserPrincipal member = member();
        boolean allowed = member == null
                ? limiter.tryGuestRead()
                : limiter.tryMemberRead(member.userId().toString());
        if (allowed) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(limiter.retryAfterSeconds()));
    }

    private ChatReadRateLimiter limiterFor(HttpServletRequest request) {
        if (ChatHistoryPaths.isHistoryRead(request)) {
            return historyLimiter;
        }
        if (ChatNoticePaths.isNoticeRead(request)) {
            return noticeLimiter;
        }
        return null;
    }

    /** 인증 요청이면 그 신원, 아니면 {@code null}. 기준은 {@link EnterRateLimitFilter} 와 같다(principal 타입). */
    private static LiveUserPrincipal member() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof LiveUserPrincipal principal) {
            return principal;
        }
        return null;
    }
}
