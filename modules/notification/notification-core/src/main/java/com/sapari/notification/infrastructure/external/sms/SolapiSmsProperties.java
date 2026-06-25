package com.sapari.notification.infrastructure.external.sms;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SOLAPI 문자 발송에 필요한 credential, 발신번호 설정을 담는다.
 * API key와 secret은 코드에 저장하지 않고 환경변수/Secret을 통해 주입한다.
 */
@ConfigurationProperties(prefix = "sapari.customer-phone-verification.sms.solapi")
public record SolapiSmsProperties(
        String apiKey,
        String apiSecret,
        String from
) {
}
