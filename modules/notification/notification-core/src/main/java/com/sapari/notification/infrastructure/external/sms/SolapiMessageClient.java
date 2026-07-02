package com.sapari.notification.infrastructure.external.sms;

/**
 * SOLAPI SDK 발송 호출을 감싸는 작은 adapter seam이다.
 * SMS sender 테스트가 SDK static factory나 외부 네트워크에 의존하지 않도록 분리한다.
 */
interface SolapiMessageClient {

    /**
     * SOLAPI 문자 메시지를 발송한다.
     *
     * @throws Exception SDK 발송 실패 또는 provider 응답 실패
     */
    void send(String from, String to, String text) throws Exception;
}
