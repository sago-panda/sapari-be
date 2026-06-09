package com.sapari.customer.infrastructure.oauth;

import java.time.LocalDate;
import java.util.Map;

import com.sapari.user.model.ProviderType;
import com.sapari.user.model.UserGender;

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
