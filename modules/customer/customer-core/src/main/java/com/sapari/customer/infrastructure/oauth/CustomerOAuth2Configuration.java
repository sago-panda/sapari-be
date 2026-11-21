package com.sapari.customer.infrastructure.oauth;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 구매자 소셜 로그인 인가 요청 쿠키 설정을 등록한다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OAuth2AuthorizationRequestCookieProperties.class)
public class CustomerOAuth2Configuration {
}
