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

import com.sapari.common.securityjwt.jwt.JwtTokenClaims;
import com.sapari.common.securityjwt.jwt.JwtTokenProvider;
import com.sapari.common.securityjwt.jwt.JwtTokenType;

/**
 * 존 경계를 넘는 stateless 인증 필터.
 *
 * <p>홈랩이 발급한 access JWT를 <b>서명·만료 + claims</b>만으로 검증한다. 공통
 * {@code JwtAuthenticationFilter}와 달리 user DB({@code UserDetailsService})·폐기 저장소(Redis)에
 * 의존하지 않으므로, live-app은 홈랩의 stateful 자산 없이 독립적으로 인가를 수행한다.
 *
 * <p>트레이드오프: 로그아웃/차단된 토큰은 access TTL이 만료될 때까지 유효하다(폐기 갭).
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

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        claims.userId().toString(),
                        null,
                        List.of(authority)
                );

        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 인증 성공 후 MDC에 userId 주입 → 이후 모든 로그 라인에 부착 (정리는 MdcContextFilter가 담당)
        MDC.put("userId", claims.userId().toString());
    }
}
