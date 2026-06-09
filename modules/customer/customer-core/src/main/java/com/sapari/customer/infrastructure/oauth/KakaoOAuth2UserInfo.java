package com.sapari.customer.infrastructure.oauth;

import java.time.LocalDate;
import java.util.Collections;
import java.util.Map;

import com.sapari.user.model.ProviderType;
import com.sapari.user.model.UserGender;

public class KakaoOAuth2UserInfo implements OAuth2UserInfo {

    private static final String KAKAO_ACCOUNT_ATTRIBUTE = "kakao_account";
    private static final String PROFILE_ATTRIBUTE = "profile";

    private final Map<String, Object> attributes;
    private final Map<String, Object> kakaoAccount;
    private final Map<String, Object> profile;

    public KakaoOAuth2UserInfo(Map<String, Object> attributes) {
        this.attributes = attributes;
        this.kakaoAccount = getMap(attributes, KAKAO_ACCOUNT_ATTRIBUTE);
        this.profile = getMap(kakaoAccount, PROFILE_ATTRIBUTE);
    }

    @Override
    public ProviderType provider() {
        return ProviderType.KAKAO;
    }

    @Override
    public String providerId() {
        return OAuth2ProfileParser.getString(attributes, "id");
    }

    @Override
    public String providerEmail() {
        return OAuth2ProfileParser.getString(kakaoAccount, "email");
    }

    @Override
    public String name() {
        return OAuth2ProfileParser.getString(kakaoAccount, "name");
    }

    @Override
    public String nickname() {
        return OAuth2ProfileParser.getString(profile, "nickname");
    }

    @Override
    public String phoneNumber() {
        return OAuth2ProfileParser.normalizePhoneNumber(
                OAuth2ProfileParser.getString(kakaoAccount, "phone_number")
        );
    }

    @Override
    public String profileImageUrl() {
        return OAuth2ProfileParser.getString(profile, "profile_image_url");
    }

    @Override
    public UserGender gender() {
        return OAuth2ProfileParser.parseGender(
                OAuth2ProfileParser.getString(kakaoAccount, "gender")
        );
    }

    @Override
    public LocalDate birthDate() {
        return OAuth2ProfileParser.parseBirthDate(
                OAuth2ProfileParser.getString(kakaoAccount, "birthyear"),
                OAuth2ProfileParser.getString(kakaoAccount, "birthday")
        );
    }

    @Override
    public Map<String, Object> attributes() {
        return attributes;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getMap(Map<String, Object> source, String key) {
        Object value = source.get(key);

        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }

        return Collections.emptyMap();
    }

}
