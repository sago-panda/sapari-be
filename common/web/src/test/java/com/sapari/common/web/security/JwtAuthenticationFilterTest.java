package com.sapari.common.web.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import jakarta.servlet.ServletException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

import com.sapari.common.web.security.jwt.JwtProperties;
import com.sapari.common.web.security.jwt.JwtSubject;
import com.sapari.common.web.security.jwt.JwtTokenProvider;
import com.sapari.global.time.TimeProvider;

@DisplayName("JWT 인증 필터 테스트")
class JwtAuthenticationFilterTest {

    private static final String SECRET = "test-secret-key-for-jwt-provider-32bytes";
    private static final String ISSUER = "auth-service-test";

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("유효한 Access Token이면 SecurityContext에 인증 객체를 저장한다")
    void doFilterStoresAuthenticationWhenAccessTokenIsValid() throws ServletException, IOException {
        // given
        JwtTokenProvider jwtTokenProvider = createProvider();
        UUID userId = UUID.randomUUID();
        String token = jwtTokenProvider.createAccessToken(jwtSubject(userId, "USER"));
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
                jwtTokenProvider,
                userDetailsService(userId, "USER", "ACTIVE"),
                activeTokenChecker()
        );
        MockHttpServletRequest request = requestWithAuthorization("Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        // when
        filter.doFilter(request, response, new MockFilterChain());

        // then
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getName()).isEqualTo(userId.toString());
        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_USER");
    }

    @Test
    @DisplayName("Authorization 헤더가 없으면 인증하지 않는다")
    void doFilterDoesNotAuthenticateWhenAuthorizationHeaderIsMissing() throws ServletException, IOException {
        // given
        JwtAuthenticationFilter filter = createFilterWithUnreachableUserDetailsService();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        // when
        filter.doFilter(request, response, new MockFilterChain());

        // then
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("Bearer 형식이 아니면 인증하지 않는다")
    void doFilterDoesNotAuthenticateWhenAuthorizationHeaderIsNotBearer() throws ServletException, IOException {
        // given
        JwtAuthenticationFilter filter = createFilterWithUnreachableUserDetailsService();
        MockHttpServletRequest request = requestWithAuthorization("Basic token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // when
        filter.doFilter(request, response, new MockFilterChain());

        // then
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("Refresh Token이면 인증하지 않는다")
    void doFilterDoesNotAuthenticateWhenTokenTypeIsRefresh() throws ServletException, IOException {
        // given
        JwtTokenProvider jwtTokenProvider = createProvider();
        String token = jwtTokenProvider.createRefreshToken(jwtSubject(UUID.randomUUID(), "USER"));
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
                jwtTokenProvider,
                unreachableUserDetailsService(),
                activeTokenChecker()
        );
        MockHttpServletRequest request = requestWithAuthorization("Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        // when
        filter.doFilter(request, response, new MockFilterChain());

        // then
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("비활성 사용자이면 인증하지 않는다")
    void doFilterDoesNotAuthenticateWhenUserIsDisabled() throws ServletException, IOException {
        // given
        JwtTokenProvider jwtTokenProvider = createProvider();
        UUID userId = UUID.randomUUID();
        String token = jwtTokenProvider.createAccessToken(jwtSubject(userId, "USER"));
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
                jwtTokenProvider,
                userDetailsService(userId, "USER", "DELETED"),
                activeTokenChecker()
        );
        MockHttpServletRequest request = requestWithAuthorization("Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        // when
        filter.doFilter(request, response, new MockFilterChain());

        // then
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("잠긴 사용자이면 인증하지 않는다")
    void doFilterDoesNotAuthenticateWhenUserIsLocked() throws ServletException, IOException {
        // given
        JwtTokenProvider jwtTokenProvider = createProvider();
        UUID userId = UUID.randomUUID();
        String token = jwtTokenProvider.createAccessToken(jwtSubject(userId, "USER"));
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
                jwtTokenProvider,
                userDetailsService(userId, "USER", "SUSPENDED"),
                activeTokenChecker()
        );
        MockHttpServletRequest request = requestWithAuthorization("Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        // when
        filter.doFilter(request, response, new MockFilterChain());

        // then
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("기존 인증 객체가 있으면 덮어쓰지 않는다")
    void doFilterDoesNotOverrideExistingAuthentication() throws ServletException, IOException {
        // given
        JwtTokenProvider jwtTokenProvider = createProvider();
        UUID userId = UUID.randomUUID();
        String token = jwtTokenProvider.createAccessToken(jwtSubject(userId, "USER"));
        Authentication existingAuthentication = new TestingAuthenticationToken("existing", null);
        SecurityContextHolder.getContext().setAuthentication(existingAuthentication);
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
                jwtTokenProvider,
                unreachableUserDetailsService(),
                activeTokenChecker()
        );
        MockHttpServletRequest request = requestWithAuthorization("Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        // when
        filter.doFilter(request, response, new MockFilterChain());

        // then
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(existingAuthentication);
    }

    @Test
    @DisplayName("폐기된 Access Token이면 인증하지 않는다")
    void doFilterDoesNotAuthenticateWhenAccessTokenIsRevoked() throws ServletException, IOException {
        // given
        JwtTokenProvider jwtTokenProvider = createProvider();
        UUID userId = UUID.randomUUID();
        String token = jwtTokenProvider.createAccessToken(jwtSubject(userId, "USER"));
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
                jwtTokenProvider,
                userDetailsService(userId, "USER", "ACTIVE"),
                revokedTokenChecker()
        );
        MockHttpServletRequest request = requestWithAuthorization("Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        // when
        filter.doFilter(request, response, new MockFilterChain());

        // then
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private JwtTokenProvider createProvider() {
        return new JwtTokenProvider(new JwtProperties(ISSUER, SECRET, 3600L, 1209600L), timeProvider());
    }

    private TimeProvider timeProvider() {
        return new TimeProvider(Clock.fixed(Instant.now(), ZoneOffset.UTC));
    }

    private JwtSubject jwtSubject(UUID userId, String role) {
        return new JwtSubject(userId, role, "member", "member@example.com");
    }

    private UserDetailsService userDetailsService(UUID userId, String role, String status) {
        return username -> userDetails(userId, role, status);
    }

    private UserDetails userDetails(UUID userId, String role, String status) {
        return new TestUserDetails(userId, role, status);
    }

    private JwtAuthenticationFilter createFilterWithUnreachableUserDetailsService() {
        return new JwtAuthenticationFilter(
                createProvider(),
                unreachableUserDetailsService(),
                activeTokenChecker()
        );
    }

    private UserDetailsService unreachableUserDetailsService() {
        return username -> {
            throw new AssertionError("UserDetailsService should not be called.");
        };
    }

    private AccessTokenRevocationChecker activeTokenChecker() {
        return accessToken -> false;
    }

    private AccessTokenRevocationChecker revokedTokenChecker() {
        return accessToken -> true;
    }

    private MockHttpServletRequest requestWithAuthorization(String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", authorization);
        return request;
    }

    private record TestUserDetails(UUID userId, String role, String status) implements UserDetails {

        @Override
        public java.util.Collection<? extends GrantedAuthority> getAuthorities() {
            return java.util.List.of(new SimpleGrantedAuthority("ROLE_" + role));
        }

        @Override
        public String getPassword() {
            return null;
        }

        @Override
        public String getUsername() {
            return userId.toString();
        }

        @Override
        public boolean isAccountNonExpired() {
            return true;
        }

        @Override
        public boolean isAccountNonLocked() {
            return !"SUSPENDED".equals(status);
        }

        @Override
        public boolean isCredentialsNonExpired() {
            return true;
        }

        @Override
        public boolean isEnabled() {
            return "ACTIVE".equals(status);
        }
    }
}
