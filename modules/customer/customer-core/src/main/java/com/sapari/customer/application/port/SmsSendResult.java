package com.sapari.customer.application.port;

/**
 * SMS 발송 provider의 발송 결과를 표현한다.
 */
public record SmsSendResult(boolean success, String providerMessageId) {
}
