package com.sapari.customer.infrastructure.external.sms;

import lombok.extern.slf4j.Slf4j;

import com.solapi.sdk.message.exception.SolapiMessageNotReceivedException;
import com.solapi.sdk.message.model.Message;
import com.solapi.sdk.message.service.DefaultMessageService;

/**
 * SOLAPI 공식 Java/Kotlin SDK의 Message 발송 API를 사용하는 실제 client adapter다.
 */
@Slf4j
class SolapiSdkMessageClient implements SolapiMessageClient {

    private final DefaultMessageService messageService;

    SolapiSdkMessageClient(DefaultMessageService messageService) {
        this.messageService = messageService;
    }

    /**
     * Message 객체에 발신번호, 수신번호, 본문을 설정한 뒤 발송한다.
     */
    @Override
    public void send(String from, String to, String text) throws Exception {
        Message message = new Message();
        message.setFrom(from);
        message.setTo(to);
        message.setText(text);

        try {
            messageService.send(message);
        } catch (SolapiMessageNotReceivedException e) {
            int failedCount = e.getFailedMessageList() == null ? 0 : e.getFailedMessageList().size();
            log.warn("SOLAPI message was not received. failedCount={}", failedCount);
            throw e;
        }
    }
}
