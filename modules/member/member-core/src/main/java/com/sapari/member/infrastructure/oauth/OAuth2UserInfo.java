package com.sapari.member.infrastructure.oauth;

import java.util.Map;

import com.sapari.user.domain.model.ProviderType;

public interface OAuth2UserInfo {

    ProviderType provider();

    String providerId();

    String providerEmail();

    String name();

    String profileImageUrl();

    Map<String, Object> attributes();
}
