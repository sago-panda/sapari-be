package com.sapari.common.web.security;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.web.filter.OncePerRequestFilter;

import com.sapari.common.web.security.jwt.JwtTokenProvider;
import com.sapari.common.web.security.jwt.JwtTokenType;

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
                    && SecurityContextHolder.getContext().getAuthentication() == null
                    && jwtTokenProvider.validateToken(token)
                    && isAccessToken(token)
                    && isNotRevoked(token)) {
                authenticate(token);
            }
        } catch (AuthenticationException | IllegalArgumentException e) {
            SecurityContextHolder.clearContext();
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

    private boolean isAccessToken(String token) {
        return jwtTokenProvider.getTokenType(token) == JwtTokenType.ACCESS;
    }

    private boolean isNotRevoked(String token) {
        return !accessTokenRevocationChecker.isRevoked(token);
    }

    private void authenticate(String token) {
        UUID userId = jwtTokenProvider.getUserId(token);
        UserDetails userDetails = userDetailsService.loadUserByUsername(userId.toString());

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
    }
}
