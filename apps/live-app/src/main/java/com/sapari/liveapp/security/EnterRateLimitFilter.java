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
 * 입장(=룸 토큰 발급) 상한을 거는 자리. 형제 {@code WebhookRateLimitFilter} 와 같은 모양이다.
 *
 * <p><b>왜 컨트롤러가 아닌가.</b> {@code apps/AGENTS.md} 가 못 박는다 — "실패는 던진다,
 * 조립하지 않는다. 컨트롤러가 에러 본문을 만들면 위반" 이고, 원시 DTO 면제는 <b>성공 본문에
 * 한정</b>되며 같은 문장이 "에러는 봉투가 유지된다" 를 적는다. 한때 컨트롤러가 429 를 직접
 * 돌려줬고 그 주석이 마이그레이션 단락을 근거로 들었는데, 그 단락은 반대를 말한다.
 *
 * <p><b>그러면 왜 던지지 않는가.</b> 던지면 공용 {@code GlobalExceptionHandler} 가 4xx 를
 * 거부마다 {@code log.warn} 으로 남긴다(실측: 1/s 버킷에 51회 호출 → 거부 50건에 핸들러 WARN
 * 50줄). 리미터의 10초 억제는 자기 로거에만 걸리므로 폭주 규모에 로그가 비례하고, CPU 대신
 * 로그 I/O 가 포화되며 그 사이 다른 WARN·ERROR 이 묻힌다. 필터는 규약 밖이라 응답을 직접
 * 세울 수 있고, 그것이 형제가 이미 하는 일이다.
 *
 * <p><b>본문을 싣지 않는 대가.</b> 같은 엔드포인트의 다른 에러는 봉투로 나가는데 이 429 는
 * {@code error.code} 가 없다. {@code error.code} 를 읽는 클라이언트·로그 파이프라인에 이
 * 응답은 빈칸이다. 그 대가를 받는 이유는 위의 로그 증식이고, 형제도 같은 선택을 했다
 * (그쪽은 호출자가 기계라는 추가 근거가 있다). 상태코드와 {@code Retry-After} 가 전부다.
 *
 * <p><b>시큐리티 체인 안, JWT 필터 <i>뒤</i>에 선다.</b> 인증 여부로 버킷을 가르므로
 * {@code SecurityContextHolder} 가 채워진 뒤여야 한다. 체인 밖 최상위 필터로 두면 그 순서가
 * 보장되지 않는다({@code springSecurityFilterChain} 자체가 하나의 필터다).
 */
@RequiredArgsConstructor
public class EnterRateLimitFilter extends OncePerRequestFilter {

    private final EnterRateLimiter rateLimiter;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!EnterPaths.isEnterRequest(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        LiveUserPrincipal member = member();
        boolean allowed = member != null
                ? rateLimiter.tryMemberEntry(member.userId().toString())
                : rateLimiter.tryGuestEntry();
        if (allowed) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(rateLimiter.retryAfterSeconds()));
    }

    /**
     * 인증 요청이면 그 신원, 아니면 {@code null}. 회원 쪽은 {@code sub} 로 샤드를 고른다.
     *
     * <p>{@code isAuthenticated()} 만 보지 않는다 — 익명 인증이 채워져 있으면 그것도 참이다.
     * 컨트롤러의 분기 기준이 {@code @AuthenticationPrincipal LiveUserPrincipal} 이므로
     * <b>같은 기준</b>으로 본다: principal 이 그 타입인가. 두 기준이 갈리면 상한이 걸리는
     * 집단과 토큰에 실리는 신원이 달라진다.
     */
    private static LiveUserPrincipal member() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof LiveUserPrincipal principal) {
            return principal;
        }
        return null;
    }
}
