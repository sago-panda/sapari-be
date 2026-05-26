package com.sapari.member.infrastructure.oauth;

import java.util.Collections;
import java.util.Map;

import com.sapari.user.domain.model.ProviderType;

public class NaverOAuth2UserInfo implements OAuth2UserInfo {

    private static final String RESPONSE_ATTRIBUTE = "response";

    private final Map<String, Object> attributes;
    private final Map<String, Object> response;

    public NaverOAuth2UserInfo(Map<String, Object> attributes) {
        this.attributes = attributes;
        this.response = getMap(attributes, RESPONSE_ATTRIBUTE);
    }

    @Override
    public ProviderType provider() {
        return ProviderType.NAVER;
    }

    @Override
    public String providerId() {
        return getString(response, "id");
    }

    @Override
    public String providerEmail() {
        return getString(response, "email");
    }

    @Override
    public String name() {
        return getString(response, "name");
    }

    @Override
    public String profileImageUrl() {
        return getString(response, "profile_image");
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
