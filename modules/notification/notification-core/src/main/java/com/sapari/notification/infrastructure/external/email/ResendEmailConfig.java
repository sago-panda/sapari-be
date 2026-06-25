package com.sapari.notification.infrastructure.external.email;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import com.resend.Resend;

/**
 * Resend SDK client를 Spring bean으로 등록한다.
 * API key 누락은 운영 발송 실패를 늦게 발견하지 않도록 시작 시점에 차단한다.
 */
@Configuration
@EnableConfigurationProperties(ResendEmailProperties.class)
public class ResendEmailConfig {

    /**
     * Resend SDK client를 생성하고 API key 누락 시 애플리케이션 시작을 실패시킨다.
     */
    @Bean
    public Resend resend(ResendEmailProperties properties) {
        if (!StringUtils.hasText(properties.apiKey())) {
            throw new IllegalArgumentException("sapari.email.resend.api-key must not be blank");
        }
        return new Resend(properties.apiKey());
    }
}
