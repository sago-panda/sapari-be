package com.sapari.customer.infrastructure.oauth;

import lombok.Getter;
import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "customer.oauth2.redirect")
public class CustomerOAuthRedirectProperties {

    private String loginSuccessUrl;
    private String signupUrl;
}
