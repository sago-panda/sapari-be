package com.sapari.member.infrastructure.oauth;

import java.time.LocalDate;
import java.util.Collections;
import java.util.Map;

import com.sapari.user.model.ProviderType;
import com.sapari.user.model.UserGender;

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
        return OAuth2ProfileParser.getString(response, "id");
    }

    @Override
    public String providerEmail() {
        return OAuth2ProfileParser.getString(response, "email");
    }

    @Override
    public String name() {
        return OAuth2ProfileParser.getString(response, "name");
    }

    @Override
    public String nickname() {
        return OAuth2ProfileParser.getString(response, "nickname");
    }

    @Override
    public String phoneNumber() {
        return OAuth2ProfileParser.normalizePhoneNumber(
                OAuth2ProfileParser.getString(response, "mobile")
        );
    }

    @Override
    public String profileImageUrl() {
        return OAuth2ProfileParser.getString(response, "profile_image");
    }

    @Override
    public UserGender gender() {
        return OAuth2ProfileParser.parseGender(
                OAuth2ProfileParser.getString(response, "gender")
        );
    }

    @Override
    public LocalDate birthDate() {
        return OAuth2ProfileParser.parseBirthDate(
                OAuth2ProfileParser.getString(response, "birthyear"),
                OAuth2ProfileParser.getString(response, "birthday")
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
