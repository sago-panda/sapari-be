package com.sapari.user.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

@DisplayName("JWT 사용자 인증 정보 테스트")
class JwtUserDetailsTest {

    @Test
    @DisplayName("role 문자열을 ROLE_ prefix 권한으로 변환한다")
    void getAuthoritiesReturnsRolePrefixedAuthority() {
        // given
        JwtUserDetails userDetails = new JwtUserDetails(
                new JwtUserDetailsInfo(UUID.randomUUID(), "USER", "ACTIVE")
        );

        // when
        var authorities = userDetails.getAuthorities();

        // then
        assertThat(authorities)
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_USER");
    }

    @Test
    @DisplayName("username은 userId 문자열을 반환한다")
    void getUsernameReturnsUserIdString() {
        // given
        UUID userId = UUID.randomUUID();
        JwtUserDetails userDetails = new JwtUserDetails(
                new JwtUserDetailsInfo(userId, "USER", "ACTIVE")
        );

        // when
        String username = userDetails.getUsername();

        // then
        assertThat(username).isEqualTo(userId.toString());
    }

    @Test
    @DisplayName("JWT 인증에서는 password를 사용하지 않는다")
    void getPasswordReturnsNull() {
        // given
        JwtUserDetails userDetails = new JwtUserDetails(
                new JwtUserDetailsInfo(UUID.randomUUID(), "USER", "ACTIVE")
        );

        // when
        String password = userDetails.getPassword();

        // then
        assertThat(password).isNull();
    }

    @Test
    @DisplayName("ACTIVE 상태는 활성 사용자로 판단한다")
    void isEnabledReturnsTrueWhenStatusIsActive() {
        // given
        JwtUserDetails userDetails = new JwtUserDetails(
                new JwtUserDetailsInfo(UUID.randomUUID(), "USER", "ACTIVE")
        );

        // when
        boolean enabled = userDetails.isEnabled();

        // then
        assertThat(enabled).isTrue();
    }

    @Test
    @DisplayName("DELETED 상태는 비활성 사용자로 판단한다")
    void isEnabledReturnsFalseWhenStatusIsDeleted() {
        // given
        JwtUserDetails userDetails = new JwtUserDetails(
                new JwtUserDetailsInfo(UUID.randomUUID(), "USER", "DELETED")
        );

        // when
        boolean enabled = userDetails.isEnabled();

        // then
        assertThat(enabled).isFalse();
    }

    @Test
    @DisplayName("SUSPENDED 상태는 잠긴 사용자로 판단한다")
    void isAccountNonLockedReturnsFalseWhenStatusIsSuspended() {
        // given
        JwtUserDetails userDetails = new JwtUserDetails(
                new JwtUserDetailsInfo(UUID.randomUUID(), "USER", "SUSPENDED")
        );

        // when
        boolean accountNonLocked = userDetails.isAccountNonLocked();

        // then
        assertThat(accountNonLocked).isFalse();
    }
}
