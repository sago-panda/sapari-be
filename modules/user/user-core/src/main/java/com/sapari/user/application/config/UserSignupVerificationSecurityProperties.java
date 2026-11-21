package com.sapari.user.application.config;

import lombok.Getter;
import lombok.Setter;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 회원가입 연락처 인증에서 phone/email key와 codeHash를 만들 때 사용하는 공용 HMAC secret 설정이다.
 */
@Getter
@Setter
@Component
@Validated
@ConfigurationProperties(prefix = "sapari.signup-verification.security")
public class UserSignupVerificationSecurityProperties {

    @NotBlank
    private String hmacSecret;
}
