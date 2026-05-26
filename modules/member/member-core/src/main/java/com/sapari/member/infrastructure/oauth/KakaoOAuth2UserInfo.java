package com.sapari.member.infrastructure.oauth;

import java.util.Collections;
import java.util.Map;

import com.sapari.user.domain.model.ProviderType;

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
        return getString(attributes, "id");
    }

    @Override
    public String providerEmail() {
        return getString(kakaoAccount, "email");
    }

    @Override
    public String name() {
        return getString(profile, "nickname");
    }

    @Override
    public String profileImageUrl() {
        return getString(profile, "profile_image_url");
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

    private String getString(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
