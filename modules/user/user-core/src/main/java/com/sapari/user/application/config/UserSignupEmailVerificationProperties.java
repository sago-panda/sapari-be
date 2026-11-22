package com.sapari.user.application.config;

import java.time.Duration;

import lombok.Getter;
import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 회원가입 이메일 인증 TTL과 실패 횟수 정책을 외부 설정으로 관리한다.
 * 인증번호 유효 시간은 사용자 안내 문구와 어긋나지 않도록 5분으로 고정한다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "sapari.signup-verification.email")
public class UserSignupEmailVerificationProperties {

    private static final Duration FIXED_CODE_TTL = Duration.ofMinutes(5);

    private Duration codeTtl = FIXED_CODE_TTL;
    private Duration verifiedTtl = Duration.ofMinutes(30);
    private Duration resendCooldown = Duration.ofSeconds(60);
    private int maxAttempts = 5;

    /**
     * 회원가입 이메일 인증번호 유효 시간은 안내 문구와 동일하게 5분 정책으로 고정한다.
     * yml/env에서 다른 값을 주입하면 설정 바인딩 중 예외를 던져 애플리케이션 시작을 실패시킨다.
     */
    public void setCodeTtl(Duration codeTtl) {
        if (!FIXED_CODE_TTL.equals(codeTtl)) {
            throw new IllegalArgumentException("signup email verification codeTtl must be 5 minutes");
        }
        this.codeTtl = codeTtl;
    }
}
