package com.sapari.liveapp.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.sapari.common.securityjwt.store.AccessTokenRevocationChecker;
import com.sapari.common.securityjwt.store.SessionRevocationChecker;

/**
 * 폐기된 access 토큰의 인증을 거둬 가는 자리.
 *
 * <p><b>왜 필요한가.</b> {@link StatelessJwtAuthenticationFilter} 는 서명·만료·claims 만 보므로,
 * 로그아웃·차단·역할 강등된 토큰이 자기 {@code exp} 까지 인증을 통과했다. 그 사이 입장은
 * <b>룸 토큰을 새로 발급</b>하고({@code EnterLiveService}) 그 토큰 한 장이 chat 의 유일한 인증
 * 수단이다({@code RoomTokenVerifier}) — 즉 강등 전 역할이 담긴 채팅 입장권이 폐기 후에 새로
 * 만들어졌다. 변경 경로도 같이 열려 있었다: 강퇴·밴 해제·사전 관리는 {@code LiveSecurityConfig}
 * 에서 SELLER·ADMIN 역할로 갈리고, 그 역할의 출처가 바로 이 claims 다.
 *
 * <p><b>왜 JWT 필터 안이 아니라 별 필터인가 — 순서가 비용이다.</b> 입장 상한
 * ({@link EnterRateLimitFilter}) 은 인증 여부로 버킷을 가르므로 JWT 필터 <b>뒤</b>에 선다. 폐기
 * 조회를 JWT 필터에 넣으면 상한에 걸려 <b>거부될 요청까지</b> Redis 를 한 번씩 치고 간다 —
 * 그 상한은 CPU 천장이라 왕복 비용을 막아 주지 못한다. 상한 뒤로 내리면 거부분은 왕복이 0 이고,
 * 그런데도 {@code AuthorizationFilter} 보다는 앞이라 <b>인가 판정 전에</b> 컨텍스트가 비워진다.
 * 입장 밖 경로는 상한 필터가 {@code EnterPaths.isEnterRequest} 로 즉시 통과시키므로 이 순서가
 * 그쪽 비용을 늘리지 않는다.
 *
 * <p><b>두 축을 함께 본다.</b> {@code jti}(토큰 한 장)와 {@code sid}(로그인 세션)는 다른 사건이다 —
 * 전자는 그 토큰만, 후자는 그 세션의 모든 토큰을 접는다. 둘 다 {@code JwtTokenProvider} 가
 * <b>필수로 발급하고 필수로 검증</b>하므로({@code getRequiredTokenId}/{@code getRequiredSessionId}
 * 는 없으면 던진다) 이 필터가 볼 값이 비어 있는 access 토큰은 애초에 인증을 통과하지 못한다.
 *
 * <p><b>조회가 실패하면 인증을 거둔다(fail-closed).</b> 공통 {@code JwtAuthenticationFilter} 의
 * 폐기 조회가 같은 방향이라({@code catch (RuntimeException)} → 통과시키지 않음) 두 앱이 같은
 * 답을 준다. 대가는 Redis 장애가 live-app 의 <b>인증된</b> 요청을 전부 막는다는 것이다 — 공개
 * 조회와 게스트 입장은 principal 이 없어 이 분기에 들어오지 않으므로 계속 뜬다.
 *
 * <p>컨텍스트를 비우기만 하고 응답을 세우지 않는다. 401 본문은 체인 끝의
 * {@code JwtAuthenticationEntryPoint} 가 봉투로 만든다 — 형제 {@link EnterRateLimitFilter} 가
 * 응답을 직접 세우는 것은 거부마다 핸들러 WARN 이 쌓이는 것을 피하려는 것이고, 이쪽은 폐기
 * 토큰이라 규모가 공격자 손에 있지 않다.
 */
@Slf4j
@RequiredArgsConstructor
public class TokenRevocationFilter extends OncePerRequestFilter {

    private final AccessTokenRevocationChecker accessTokenRevocationChecker;
    private final SessionRevocationChecker sessionRevocationChecker;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        LiveUserPrincipal principal = authenticatedPrincipal();

        if (principal != null && isRevoked(request, principal)) {
            SecurityContextHolder.clearContext();
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 인증 요청이면 그 신원, 아니면 {@code null}.
     *
     * <p>판정 기준을 {@code EnterRateLimitFilter.member()} 와 같게 둔다 — principal 이
     * {@code LiveUserPrincipal} 인가. {@code isAuthenticated()} 는 익명 인증에도 참이라 기준이
     * 되지 못하고, 두 필터의 기준이 갈리면 상한을 회원으로 센 요청을 이쪽이 안 볼 수 있다.
     */
    private static LiveUserPrincipal authenticatedPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof LiveUserPrincipal principal) {
            return principal;
        }
        return null;
    }

    private boolean isRevoked(HttpServletRequest request, LiveUserPrincipal principal) {
        try {
            return accessTokenRevocationChecker.isRevoked(principal.tokenId())
                    || sessionRevocationChecker.isRevoked(principal.sessionId());
        } catch (RuntimeException e) {
            // 조회 불가는 "폐기 아님" 이 아니다. 여기서 통과시키면 Redis 가 흔들리는 동안
            // 폐기 갭이 그대로 돌아온다 — 그게 이 필터가 닫으려던 것이다.
            log.warn(
                    "폐기 조회 실패 — 인증을 거둔다. method={}, uri={}, reason={}",
                    request.getMethod(),
                    request.getRequestURI(),
                    e.getClass().getSimpleName()
            );
            return true;
        }
    }
}
