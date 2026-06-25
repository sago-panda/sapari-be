package com.sapari.user.application.config;

import lombok.Getter;
import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 회원가입 연락처 인증에서 phone/email key와 codeHash를 만들 때 사용하는 공용 HMAC secret 설정이다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "sapari.signup-verification.security")
public class UserSignupVerificationSecurityProperties {

    private String hmacSecret;
}
