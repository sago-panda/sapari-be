package com.sapari.customer.infrastructure.oauth;

import java.util.Locale;

import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import com.sapari.customer.domain.exception.CustomerErrorCode;

@Service
public class CustomerOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    private final OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate;

    public CustomerOAuth2UserService() {
        this.delegate = new DefaultOAuth2UserService();
    }

    CustomerOAuth2UserService(OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate) {
        this.delegate = delegate;
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = delegate.loadUser(userRequest);
        String registrationId = userRequest.getClientRegistration().getRegistrationId();
        OAuth2UserInfo userInfo = createOAuth2UserInfo(registrationId, oAuth2User);

        validateProviderId(userInfo);

        return new CustomerOAuth2User(
                userInfo.provider(),
                userInfo.providerId(),
                userInfo.providerEmail(),
                userInfo.name(),
                userInfo.nickname(),
                userInfo.phoneNumber(),
                userInfo.profileImageUrl(),
                userInfo.gender(),
                userInfo.birthDate(),
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
                            CustomerErrorCode.INVALID_OAUTH_PROVIDER.getCode(),
                            CustomerErrorCode.INVALID_OAUTH_PROVIDER.getMessage() + ": " + registrationId,
                            null
                    )
            );
        };
    }

    private void validateProviderId(OAuth2UserInfo userInfo) {
        if (userInfo.providerId() == null || userInfo.providerId().isBlank()) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error(
                            CustomerErrorCode.INVALID_SOCIAL_INFO.getCode(),
                            CustomerErrorCode.INVALID_SOCIAL_INFO.getMessage(),
                            null
                    )
            );
        }
    }
}
