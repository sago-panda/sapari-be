package com.sapari.notification.infrastructure.external.sms;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import com.sapari.notification.application.port.SmsSender;
import com.sapari.notification.view.MessageSendResult;

/**
 * SOLAPI 공식 SDK로 SMS 메시지를 발송한다.
 * API key/secret과 발신번호는 환경변수/Secret으로 주입하며, provider 실패 상세는 PII 보호를 위해 숨긴다.
 */
@Slf4j
@Component
public class SolapiSmsSender implements SmsSender {

    private final SolapiSmsProperties properties;
    private final SolapiMessageClient messageClient;

    SolapiSmsSender(SolapiSmsProperties properties, SolapiMessageClient messageClient) {
        this.properties = properties;
        this.messageClient = messageClient;
    }

    /**
     * SOLAPI로 SMS를 발송한다.
     * SDK 예외 메시지에는 수신번호나 인증번호 본문이 포함될 수 있으므로 원본 예외를 로그/cause로 남기지 않는다.
     */
    @Override
    public MessageSendResult send(String phoneNumber, String message) {
        if (isBlank(properties.apiKey()) || isBlank(properties.apiSecret()) || isBlank(properties.from())) {
            return new MessageSendResult(false, null);
        }

        // SOLAPI는 숫자만 있는 발신/수신번호를 기대하므로 provider 호출 직전에 표시 문자를 제거한다.
        String from = digitsOnly(properties.from());
        String to = digitsOnly(phoneNumber);

        try {
            messageClient.send(from, to, message);
            return new MessageSendResult(true, null);
        } catch (Exception e) {
            log.warn(
                    "SOLAPI SMS send failed. exceptionType={}",
                    e.getClass().getSimpleName()
            );
            return new MessageSendResult(false, null);
        }
    }

    private String digitsOnly(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\D", "");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
