package com.sapari.notification.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.notification.application.port.EmailSender;
import com.sapari.notification.application.port.SmsSender;
import com.sapari.notification.command.SendSignupVerificationEmailCommand;
import com.sapari.notification.command.SendSignupVerificationSmsCommand;
import com.sapari.notification.view.MessageSendResult;

import static org.mockito.Mockito.mock;

@DisplayName("알림 발송 서비스 테스트")
class NotificationSendServiceTest {

    private final SmsSender smsSender = mock(SmsSender.class);
    private final EmailSender emailSender = mock(EmailSender.class);
    private final NotificationSendService service = new NotificationSendService(
            smsSender,
            emailSender,
            new SignupVerificationSmsTemplate(),
            new SignupVerificationEmailTemplate()
    );

    @Test
    @DisplayName("회원가입 인증 SMS는 notification이 템플릿을 렌더링해 발송한다")
    void sendSignupVerificationSmsRendersTemplateAndDelegatesToSmsSenderPort() {
        SendSignupVerificationSmsCommand command = new SendSignupVerificationSmsCommand("01012345678", "123456");
        MessageSendResult sendResult = new MessageSendResult(true, "message-id");
        when(smsSender.send(command.phoneNumber(), "[Sapari] 인증번호는 123456입니다. 5분 내 입력해주세요."))
                .thenReturn(sendResult);

        MessageSendResult result = service.sendSignupVerificationSms(command);

        assertThat(result).isEqualTo(sendResult);
        verify(smsSender).send(command.phoneNumber(), "[Sapari] 인증번호는 123456입니다. 5분 내 입력해주세요.");
    }

    @Test
    @DisplayName("회원가입 인증 이메일은 notification이 템플릿을 렌더링해 발송한다")
    void sendSignupVerificationEmailRendersTemplateAndDelegatesToEmailSenderPort() {
        SendSignupVerificationEmailCommand command = new SendSignupVerificationEmailCommand("user@example.com", "123456");
        MessageSendResult sendResult = new MessageSendResult(true, "email-message-id");
        when(emailSender.send(command.email(), "Sapari 이메일 인증번호", new SignupVerificationEmailTemplate().render("123456")))
                .thenReturn(sendResult);

        MessageSendResult result = service.sendSignupVerificationEmail(command);

        assertThat(result).isEqualTo(sendResult);
        verify(emailSender).send(command.email(), "Sapari 이메일 인증번호", new SignupVerificationEmailTemplate().render("123456"));
    }
}
