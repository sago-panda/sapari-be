package com.sapari.notification.application.service;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import com.sapari.notification.application.port.EmailSender;
import com.sapari.notification.application.port.SmsSender;
import com.sapari.notification.command.SendSignupVerificationEmailCommand;
import com.sapari.notification.command.SendSignupVerificationSmsCommand;
import com.sapari.notification.port.NotificationSendUseCase;
import com.sapari.notification.view.MessageSendResult;

/**
 * 사용자 대상 메시지 발송 요청을 provider adapter로 위임한다.
 * 채널별 문구 소유 경계를 유지해 호출 도메인이 외부 provider 세부사항을 알지 않게 한다.
 */
@Service
@RequiredArgsConstructor
public class NotificationSendService implements NotificationSendUseCase {

    private final SmsSender smsSender;
    private final EmailSender emailSender;
    private final SignupVerificationSmsTemplate signupVerificationSmsTemplate;

    /**
     * 회원가입 인증 SMS는 notification이 소유한 SMS 템플릿을 렌더링해 발송한다.
     */
    @Override
    public MessageSendResult sendSignupVerificationSms(SendSignupVerificationSmsCommand command) {
        return smsSender.send(command.phoneNumber(), signupVerificationSmsTemplate.render(command.verificationCode()));
    }

    /**
     * 회원가입 인증 이메일은 Resend 관리 템플릿을 사용하도록 이메일과 인증번호만 provider port로 넘긴다.
     */
    @Override
    public MessageSendResult sendSignupVerificationEmail(SendSignupVerificationEmailCommand command) {
        return emailSender.sendSignupVerification(command.email(), command.verificationCode());
    }
}
