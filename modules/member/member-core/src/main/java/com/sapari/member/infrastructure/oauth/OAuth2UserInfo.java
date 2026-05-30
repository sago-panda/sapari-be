package com.sapari.member.infrastructure.oauth;

import java.time.LocalDate;
import java.util.Map;

import com.sapari.user.domain.model.ProviderType;
import com.sapari.user.domain.model.UserGender;

public interface OAuth2UserInfo {

    ProviderType provider();

    String providerId();

    String providerEmail();

    String name();

    String nickname();

    String phoneNumber();

    String profileImageUrl();

    UserGender gender();

    LocalDate birthDate();

    Map<String, Object> attributes();
}
