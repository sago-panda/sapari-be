package com.sapari.notification.view;

/**
 * 외부 메시지 provider의 발송 접수 결과를 표현한다.
 */
public record MessageSendResult(boolean success, String providerMessageId) {
}
