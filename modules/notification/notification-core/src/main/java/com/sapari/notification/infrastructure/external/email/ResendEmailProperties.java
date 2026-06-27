package com.sapari.notification.infrastructure.external.email;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Resend 이메일 발송 설정이다.
 * API key, 발신자, template id는 환경변수/Secret을 통해 주입한다.
 */
@ConfigurationProperties(prefix = "sapari.notification.email")
public record ResendEmailProperties(
        String from,
        String signupVerificationTemplateId,
        Resend resend
) {

    public record Resend(String apiKey) {
    }

    /**
     * 중첩 설정이 비어 있어도 config 검증 단계에서 누락을 판별할 수 있게 API key만 안전하게 꺼낸다.
     */
    public String apiKey() {
        return resend == null ? null : resend.apiKey();
    }
}
