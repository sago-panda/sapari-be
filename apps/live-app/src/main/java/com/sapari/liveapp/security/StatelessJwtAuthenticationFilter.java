package com.sapari.liveapp.security;

import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.sapari.global.logging.LogFields;
import com.sapari.common.securityjwt.jwt.JwtTokenClaims;
import com.sapari.common.securityjwt.jwt.JwtTokenProvider;
import com.sapari.common.securityjwt.jwt.JwtTokenType;

/**
 * 존 경계를 넘는 stateless 인증 필터.
 *
 * <p>홈랩이 발급한 access JWT를 <b>서명·만료 + claims</b>만으로 검증한다. 공통
 * {@code JwtAuthenticationFilter}와 달리 user DB({@code UserDetailsService})에 의존하지 않으므로,
 * live-app은 사용자 테이블 없이 신원·역할을 이 토큰 하나에서 읽는다.
 *
 * <p><b>폐기 조회는 이 필터가 하지 않는다 — 안 하는 것이 아니라 뒤에서 한다.</b>
 * {@link TokenRevocationFilter}가 입장 상한({@link EnterRateLimitFilter}) <b>뒤</b>에서
 * {@code jti}·{@code sid}를 조회해 폐기된 토큰의 인증을 거둔다. 그 자리가 여기가 아닌 이유는
 * 비용이다 — 상한은 인증 여부로 버킷을 가르므로 이 필터 뒤에 서고, 폐기 조회를 여기에 넣으면
 * 상한에 걸려 거부될 요청까지 Redis 왕복을 치고 간다. 그래서 claims에서 읽은 두 식별자를
 * {@link LiveUserPrincipal}에 실어 넘긴다(재파싱 없음).
 *
 * <p>principal name = userId(UUID 문자열)로 설정해 {@code CurrentUserIdArgumentResolver} 계약을
 * 충족하고, 권한은 role claim에 {@code ROLE_} 접두사를 붙여 구성한다({@code JwtUserDetails}와 동일 규칙).
 */
@Slf4j
@RequiredArgsConstructor
public class StatelessJwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ROLE_PREFIX = "ROLE_";

    private final JwtTokenProvider jwtTokenProvider;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String token = resolveToken(request);

        try {
            if (token != null
                    && SecurityContextHolder.getContext().getAuthentication() == null) {
                JwtTokenClaims claims = jwtTokenProvider.parseToken(token);

                if (claims.tokenType() == JwtTokenType.ACCESS) {
                    authenticate(claims);
                }
            }
        } catch (JwtException | IllegalArgumentException e) {
            SecurityContextHolder.clearContext();
            log.debug(
                    "JWT authentication failed. method={}, uri={}, reason={}",
                    request.getMethod(),
                    request.getRequestURI(),
                    e.getClass().getSimpleName()
            );
        }

        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String authorizationHeader = request.getHeader(AUTHORIZATION_HEADER);

        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            return null;
        }

        return authorizationHeader.substring(BEARER_PREFIX.length());
    }

    private void authenticate(JwtTokenClaims claims) {
        SimpleGrantedAuthority authority = new SimpleGrantedAuthority(ROLE_PREFIX + claims.role());

        // principal에 userId뿐 아니라 role·nickname·email까지 실어, 채팅 룸 토큰 발급에 필요한 신원을
        // 추가 조회 없이 확보한다. LiveUserPrincipal.getName()=userId라 CurrentUserIdArgumentResolver는 그대로 동작.
        // jti·sid는 TokenRevocationFilter가 상한 뒤에서 폐기를 조회할 때 쓴다 — 여기서 이미 파싱된 값이다.
        LiveUserPrincipal principal = new LiveUserPrincipal(
                claims.userId(), claims.tokenId(), claims.sessionId(),
                claims.role(), claims.nickname(), claims.email());

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        List.of(authority)
                );

        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 인증 성공 후 MDC에 userId 주입 → 이후 모든 로그 라인에 부착 (정리는 MdcContextFilter가 담당)
        MDC.put(LogFields.USER_ID, claims.userId().toString());
    }
}
