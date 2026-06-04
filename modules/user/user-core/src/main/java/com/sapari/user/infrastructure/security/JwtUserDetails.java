package com.sapari.user.infrastructure.security;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

@Getter
@RequiredArgsConstructor
public class JwtUserDetails implements UserDetails {

    private static final String ACTIVE_STATUS = "ACTIVE";
    private static final String SUSPENDED_STATUS = "SUSPENDED";
    private static final String ROLE_PREFIX = "ROLE_";

    private final JwtUserDetailsInfo user;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(ROLE_PREFIX + user.role()));
    }

    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public String getUsername() {
        return user.userId().toString();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return !SUSPENDED_STATUS.equals(user.status());
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return ACTIVE_STATUS.equals(user.status());
    }
}
