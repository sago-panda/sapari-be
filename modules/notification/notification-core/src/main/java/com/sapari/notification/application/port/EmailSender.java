package com.sapari.notification.application.port;

import com.sapari.notification.view.MessageSendResult;

/**
 * 이메일 발송 provider를 application 계층에서 바라보는 outbound port.
 * Resend 같은 외부 SDK는 이 port 뒤에 격리해 호출 도메인이 provider 세부 API를 알지 않게 한다.
 */
public interface EmailSender {

    /**
     * 렌더링이 끝난 제목과 HTML 본문을 외부 이메일 provider로 발송한다.
     */
    MessageSendResult send(String email, String subject, String html);
}
