package com.sapari.notification.application.port;

import com.sapari.notification.view.MessageSendResult;

/**
 * 이메일 발송 provider를 application 계층에서 바라보는 outbound port.
 * Resend 같은 외부 SDK와 template 세부사항은 이 port 뒤에 격리한다.
 */
public interface EmailSender {

    /**
     * 회원가입 이메일 인증번호를 provider에 등록된 템플릿으로 발송한다.
     * 메일 제목/본문은 백엔드가 렌더링하지 않고 provider template 변수로만 전달한다.
     */
    MessageSendResult sendSignupVerification(String email, String verificationCode);
}
