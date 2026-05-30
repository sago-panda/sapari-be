package com.sapari.member.infrastructure.oauth;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;

import com.sapari.user.domain.model.ProviderType;
import com.sapari.user.domain.model.UserGender;

public class MemberOAuth2User implements OAuth2User {

    private final ProviderType provider;
    private final String providerId;
    private final String providerEmail;
    private final String name;
    private final String nickname;
    private final String phoneNumber;
    private final String profileImageUrl;
    private final UserGender gender;
    private final LocalDate birthDate;
    private final Map<String, Object> attributes;
    private final List<? extends GrantedAuthority> authorities;

    public MemberOAuth2User(
            ProviderType provider,
            String providerId,
            String providerEmail,
            String name,
            String nickname,
            String phoneNumber,
            String profileImageUrl,
            UserGender gender,
            LocalDate birthDate,
            Map<String, Object> attributes,
            Collection<? extends GrantedAuthority> authorities
    ) {
        this.provider = provider;
        this.providerId = providerId;
        this.providerEmail = providerEmail;
        this.name = name;
        this.nickname = nickname;
        this.phoneNumber = phoneNumber;
        this.profileImageUrl = profileImageUrl;
        this.gender = gender;
        this.birthDate = birthDate;
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

    public String getNickname() {
        return nickname;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public String getProfileImageUrl() {
        return profileImageUrl;
    }

    public UserGender getGender() {
        return gender;
    }

    public LocalDate getBirthDate() {
        return birthDate;
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
