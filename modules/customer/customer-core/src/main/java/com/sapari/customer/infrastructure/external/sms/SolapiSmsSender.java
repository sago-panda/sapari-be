package com.sapari.customer.infrastructure.external.sms;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import com.sapari.customer.application.port.SmsSendResult;
import com.sapari.customer.application.port.SmsSender;
import com.sapari.customer.domain.exception.CustomerErrorCode;
import com.sapari.customer.domain.exception.CustomerException;

/**
 * SOLAPI 공식 SDK로 구매자 회원가입 인증번호 문자를 발송한다.
 * API key/secret과 발신번호는 환경변수/Secret으로 주입하며, provider 실패는 발송 지연 예외로 변환한다.
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
     * SOLAPI로 인증번호 문자를 발송한다.
     * 공식 SDK 요구사항에 맞춰 발신/수신번호는 숫자만 남기고, provider 장애는 사용자에게 발송 지연으로 안내한다.
     * SDK 예외 메시지에는 수신번호나 인증번호 본문이 포함될 수 있으므로 원본 예외를 로그/cause로 남기지 않는다.
     *
     * @throws CustomerException SOLAPI 설정이 없거나 SDK 발송이 실패한 경우
     */
    @Override
    public SmsSendResult sendVerificationCode(String phoneNumber, String code) {
        validateRequiredProperties();

        String from = digitsOnly(properties.from());
        String to = digitsOnly(phoneNumber);
        String text = verificationMessage(code);

        try {
            messageClient.send(from, to, text);
            return new SmsSendResult(true, null);
        } catch (Exception e) {
            // SDK 예외에는 to/from/text가 포함될 수 있어 exception type만 남기고 원본 cause는 버린다.
            log.warn(
                    "SOLAPI verification SMS send failed. exceptionType={}",
                    e.getClass().getSimpleName()
            );
            throw new CustomerException(CustomerErrorCode.SMS_SEND_UNAVAILABLE);
        }
    }

    private void validateRequiredProperties() {
        if (isBlank(properties.apiKey()) || isBlank(properties.apiSecret()) || isBlank(properties.from())) {
            throw new CustomerException(CustomerErrorCode.SMS_SEND_UNAVAILABLE);
        }
    }

    private String verificationMessage(String code) {
        return "[Sapari] 인증번호는 " + code + "입니다. 5분 내 입력해주세요.";
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
