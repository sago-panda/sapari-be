package com.sapari.member.infrastructure.oauth;

import java.util.Locale;

import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

@Service
public class MemberOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    private static final String UNSUPPORTED_PROVIDER_ERROR_CODE = "unsupported_oauth2_provider";
    private static final String INVALID_USER_INFO_ERROR_CODE = "invalid_oauth2_user_info";

    private final OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate;

    public MemberOAuth2UserService() {
        this(new DefaultOAuth2UserService());
    }

    MemberOAuth2UserService(OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate) {
        this.delegate = delegate;
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = delegate.loadUser(userRequest);
        String registrationId = userRequest.getClientRegistration().getRegistrationId();
        OAuth2UserInfo userInfo = createOAuth2UserInfo(registrationId, oAuth2User);

        validateProviderId(userInfo);

        return new MemberOAuth2User(
                userInfo.provider(),
                userInfo.providerId(),
                userInfo.providerEmail(),
                userInfo.name(),
                userInfo.profileImageUrl(),
                userInfo.attributes(),
                oAuth2User.getAuthorities()
        );
    }

    private OAuth2UserInfo createOAuth2UserInfo(String registrationId, OAuth2User oAuth2User) {
        return switch (registrationId.toLowerCase(Locale.ROOT)) {
            case "naver" -> new NaverOAuth2UserInfo(oAuth2User.getAttributes());
            case "kakao" -> new KakaoOAuth2UserInfo(oAuth2User.getAttributes());
            default -> throw new OAuth2AuthenticationException(
                    new OAuth2Error(
                            UNSUPPORTED_PROVIDER_ERROR_CODE,
                            "지원하지 않는 OAuth provider입니다: " + registrationId,
                            null
                    )
            );
        };
    }

    private void validateProviderId(OAuth2UserInfo userInfo) {
        if (userInfo.providerId() == null || userInfo.providerId().isBlank()) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error(
                            INVALID_USER_INFO_ERROR_CODE,
                            "OAuth provider 사용자 ID를 찾을 수 없습니다.",
                            null
                    )
            );
        }
    }
}
