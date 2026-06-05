package com.sapari.common.web.security;

import io.jsonwebtoken.JwtException;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.web.filter.OncePerRequestFilter;

import com.sapari.common.securityjwt.jwt.JwtTokenClaims;
import com.sapari.common.securityjwt.jwt.JwtTokenProvider;
import com.sapari.common.securityjwt.jwt.JwtTokenType;
import com.sapari.common.securityjwt.store.AccessTokenRevocationChecker;

@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;
    private final UserDetailsService userDetailsService;
    private final AccessTokenRevocationChecker accessTokenRevocationChecker;

    public JwtAuthenticationFilter(
            JwtTokenProvider jwtTokenProvider,
            UserDetailsService userDetailsService,
            AccessTokenRevocationChecker accessTokenRevocationChecker
    ) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.userDetailsService = userDetailsService;
        this.accessTokenRevocationChecker = accessTokenRevocationChecker;
    }

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

                if (isAccessToken(claims) && isNotRevoked(claims)) {
                    authenticate(claims);
                }
            }
        } catch (AuthenticationException | JwtException | IllegalArgumentException e) {
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

    private boolean isAccessToken(JwtTokenClaims claims) {
        return claims.tokenType() == JwtTokenType.ACCESS;
    }

    private boolean isNotRevoked(JwtTokenClaims claims) {
        return !accessTokenRevocationChecker.isRevoked(claims.tokenId());
    }

    private void authenticate(JwtTokenClaims claims) {
        UserDetails userDetails = userDetailsService.loadUserByUsername(claims.userId().toString());

        if (!userDetails.isEnabled() || !userDetails.isAccountNonLocked()) {
            return;
        }

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        userDetails,
                        null,
                        userDetails.getAuthorities()
                );

        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 인증 성공 후 MDC에 userId 주입 → 이후 모든 로그 라인에 부착 (정리는 MdcContextFilter가 담당)
        MDC.put("userId", claims.userId().toString());
    }
}
