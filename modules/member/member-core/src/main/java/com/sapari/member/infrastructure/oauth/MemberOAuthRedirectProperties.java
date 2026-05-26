package com.sapari.member.infrastructure.oauth;

import lombok.Getter;
import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "member.oauth2.redirect")
public class MemberOAuthRedirectProperties {

    private String loginSuccessUrl;
    private String signupUrl;
}
