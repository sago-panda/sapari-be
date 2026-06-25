package com.sapari.notification.application.service;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import com.sapari.notification.application.port.SmsSender;
import com.sapari.notification.command.SendSignupVerificationSmsCommand;
import com.sapari.notification.port.NotificationSendUseCase;
import com.sapari.notification.view.MessageSendResult;

/**
 * 사용자 대상 메시지 발송 요청을 provider adapter로 위임한다.
 */
@Service
@RequiredArgsConstructor
public class NotificationSendService implements NotificationSendUseCase {

    private final SmsSender smsSender;
    private final SignupVerificationSmsTemplate signupVerificationSmsTemplate;

    /**
     * 회원가입 인증 SMS 문구를 notification 소유 템플릿으로 렌더링해 발송한다.
     */
    @Override
    public MessageSendResult sendSignupVerificationSms(SendSignupVerificationSmsCommand command) {
        return smsSender.send(command.phoneNumber(), signupVerificationSmsTemplate.render(command.verificationCode()));
    }
}
