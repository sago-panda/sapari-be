package com.sapari.customer.application.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 소셜 회원가입 SID 시도 제어 정책 설정을 등록한다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SocialSignupAttemptProperties.class)
public class SocialSignupAttemptConfiguration {
}
