package com.sapari.notification.application.port;

import com.sapari.notification.view.MessageSendResult;

/**
 * SMS 발송 provider를 application 계층에서 바라보는 outbound port.
 */
public interface SmsSender {

    MessageSendResult send(String phoneNumber, String message);
}
