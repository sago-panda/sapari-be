package com.sapari.notification.infrastructure.external.email;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import com.resend.Resend;

/**
 * Resend SDK client를 Spring bean으로 등록한다.
 * provider 필수 설정 누락은 발송 시점이 아니라 애플리케이션 시작 시점에 차단한다.
 */
@Configuration
@EnableConfigurationProperties(ResendEmailProperties.class)
public class ResendEmailConfig {

    /**
     * Resend SDK client를 생성하고 API key/from/template id 누락 시 시작을 실패시킨다.
     */
    @Bean
    public Resend resend(ResendEmailProperties properties) {
        validateNotBlank(properties.apiKey(), "sapari.email.resend.api-key");
        validateNotBlank(properties.from(), "sapari.email.from");
        validateNotBlank(properties.signupVerificationTemplateId(), "sapari.email.signup-verification-template-id");
        return new Resend(properties.apiKey());
    }

    /**
     * 비어 있는 provider 설정은 명시적인 설정명과 함께 실패시켜 배포 설정 누락을 빠르게 찾게 한다.
     */
    private void validateNotBlank(String value, String propertyName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(propertyName + " must not be blank");
        }
    }
}
