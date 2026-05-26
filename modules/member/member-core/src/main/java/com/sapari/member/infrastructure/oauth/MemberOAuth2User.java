package com.sapari.member.infrastructure.oauth;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;

import com.sapari.user.domain.model.ProviderType;

public class MemberOAuth2User implements OAuth2User {

    private final ProviderType provider;
    private final String providerId;
    private final String providerEmail;
    private final String name;
    private final String profileImageUrl;
    private final Map<String, Object> attributes;
    private final List<? extends GrantedAuthority> authorities;

    public MemberOAuth2User(
            ProviderType provider,
            String providerId,
            String providerEmail,
            String name,
            String profileImageUrl,
            Map<String, Object> attributes,
            Collection<? extends GrantedAuthority> authorities
    ) {
        this.provider = provider;
        this.providerId = providerId;
        this.providerEmail = providerEmail;
        this.name = name;
        this.profileImageUrl = profileImageUrl;
        this.attributes = Map.copyOf(attributes);
        this.authorities = List.copyOf(authorities);
    }

    public ProviderType getProvider() {
        return provider;
    }

    public String getProviderId() {
        return providerId;
    }

    public String getProviderEmail() {
        return providerEmail;
    }

    public String getProfileName() {
        return name;
    }

    public String getProfileImageUrl() {
        return profileImageUrl;
    }

    @Override
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getName() {
        return providerId;
    }
}
