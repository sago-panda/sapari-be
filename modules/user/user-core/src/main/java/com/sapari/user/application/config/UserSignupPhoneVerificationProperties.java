package com.sapari.user.application.config;

import java.time.Duration;

import lombok.Getter;
import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 회원가입 휴대폰 인증 TTL, 실패 횟수, 해시 secret 정책을 외부 설정으로 관리한다.
 * 기존 설정 호환을 위해 customer phone verification prefix를 유지한다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "sapari.customer-phone-verification")
public class UserSignupPhoneVerificationProperties {

    private static final Duration FIXED_CODE_TTL = Duration.ofMinutes(5);

    private Duration codeTtl = FIXED_CODE_TTL;
    private Duration verifiedTtl = Duration.ofMinutes(10);
    private Duration resendCooldown = Duration.ofSeconds(60);
    private int maxAttempts = 5;
    private String hmacSecret;

    /**
     * 회원가입 인증번호 유효 시간은 SMS 안내 문구와 동일하게 5분 정책으로 고정한다.
     * 설정 실수로 Redis TTL과 사용자 안내 문구가 어긋나지 않도록 다른 값은 허용하지 않는다.
     */
    public void setCodeTtl(Duration codeTtl) {
        if (!FIXED_CODE_TTL.equals(codeTtl)) {
            throw new IllegalArgumentException("signup phone verification codeTtl must be 5 minutes");
        }
        this.codeTtl = codeTtl;
    }
}
