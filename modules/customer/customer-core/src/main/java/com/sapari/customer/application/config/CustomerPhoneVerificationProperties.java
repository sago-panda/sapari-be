package com.sapari.customer.application.config;

import java.time.Duration;

import lombok.Getter;
import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 구매자 회원가입 휴대폰 인증 TTL, 실패 횟수, 해시 secret 정책을 외부 설정으로 관리한다.
 * application service가 infrastructure 패키지에 의존하지 않도록 application 설정 객체로 둔다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "sapari.customer-phone-verification")
public class CustomerPhoneVerificationProperties {

    private Duration codeTtl = Duration.ofMinutes(5);
    private Duration verifiedTtl = Duration.ofMinutes(10);
    private Duration resendCooldown = Duration.ofSeconds(60);
    private int maxAttempts = 5;
    private String hmacSecret;
}
