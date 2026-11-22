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
 * 판매자 자격증명 발급(썸네일 업로드 허가 · 송출 키 재발급)에 상한을 거는 자리. 경로마다 자기
 * 리미터를 쓴다.
 *
 * <p><b>시큐리티 체인 안, JWT 필터 뒤에 선다.</b> 판매자 {@code sub} 로 샤드를 접으므로
 * {@code SecurityContextHolder} 가 채워진 뒤여야 한다.
 *
 * <p><b>신원이 없으면 세지 않고 그대로 넘긴다.</b> 두 경로 모두 {@code /api/v1/lives/**} SELLER
 * 바닥에 걸려 뒤의 인가 필터가 401·403 으로 끝내고, 서명도 LiveKit 호출도 일어나지 않는다 —
 * 막을 비용이 없는 요청을 버킷에 세면 그 몫이 정상 판매자에게서 빠진다. 공개 경로라 게스트
 * 버킷이 필요했던 형제 {@link ChatReadRateLimitFilter} 와 갈리는 지점이다.
 *
 * <p>⚠️ 이 필터는 인가 <b>앞</b>이라 역할을 모른다. 로그인한 구매자가 이 경로를 때리면 판매자
 * 버킷을 쓰고 403 을 받는다. 그래도 신원별 샤드에 묶이므로 한 계정의 피해 반경은 자기 샤드
 * 하나다 — 역할까지 보려면 인가 뒤로 내려야 하고, 그러면 거부된 요청이 이미 인가 비용을 치른 뒤다.
 *
 * <p>본문 없는 429 와 {@code Retry-After} 만 싣는 것은 형제 {@link EnterRateLimitFilter} 와 같은
 * 선택이고 이유도 같다(던지면 공용 핸들러가 거부마다 WARN 을 쓴다).
 */
@RequiredArgsConstructor
public class CredentialIssueRateLimitFilter extends OncePerRequestFilter {

    private final CredentialIssueRateLimiter thumbnailLimiter;
    private final CredentialIssueRateLimiter reissueLimiter;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        CredentialIssueRateLimiter limiter = limiterFor(request);
        if (limiter == null) {
            filterChain.doFilter(request, response);
            return;
        }
        LiveUserPrincipal principal = principal();
        if (principal == null || limiter.tryIssue(principal.userId().toString())) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(limiter.retryAfterSeconds()));
    }

    private CredentialIssueRateLimiter limiterFor(HttpServletRequest request) {
        if (CredentialIssuePaths.isThumbnailUpload(request)) {
            return thumbnailLimiter;
        }
        if (CredentialIssuePaths.isIngressReissue(request)) {
            return reissueLimiter;
        }
        return null;
    }

    /** 인증 요청이면 그 신원, 아니면 {@code null}. 기준은 {@link EnterRateLimitFilter} 와 같다(principal 타입). */
    private static LiveUserPrincipal principal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof LiveUserPrincipal found) {
            return found;
        }
        return null;
    }
}
