package com.sapari.notification.infrastructure.external.email;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailOptions;
import com.resend.services.emails.model.CreateEmailResponse;
import com.resend.services.emails.model.Template;
import com.sapari.notification.application.port.EmailSender;
import com.sapari.notification.view.MessageSendResult;

/**
 * Resend SDK 기반 이메일 sender.
 * provider 예외에는 수신 이메일/인증번호가 포함될 수 있으므로 로그에는 예외 타입만 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResendEmailSender implements EmailSender {

    private static final String ACCOUNT_VARIABLE = "account";
    private static final String CODE_VARIABLE = "code";

    private final Resend resend;
    private final ResendEmailProperties properties;

    /**
     * Resend에 등록된 회원가입 인증 템플릿 id와 변수만 전달해 이메일을 발송한다.
     * 제목/본문 HTML은 Resend template이 관리하므로 백엔드 payload에는 포함하지 않는다.
     */
    @Override
    public MessageSendResult sendSignupVerification(String email, String verificationCode) {
        Template template = Template.builder()
                .id(properties.signupVerificationTemplateId())
                .addVariable(ACCOUNT_VARIABLE, email)
                .addVariable(CODE_VARIABLE, verificationCode)
                .build();

        CreateEmailOptions options = CreateEmailOptions.builder()
                .from(properties.from())
                .to(email)
                .template(template)
                .build();

        try {
            CreateEmailResponse response = resend.emails().send(options);
            return new MessageSendResult(true, response.getId());
        } catch (ResendException e) {
            log.warn("Resend signup verification email send failed. exceptionType={}", e.getClass().getSimpleName());
            return new MessageSendResult(false, null);
        } catch (RuntimeException e) {
            log.warn("Signup verification email send failed. exceptionType={}", e.getClass().getSimpleName());
            return new MessageSendResult(false, null);
        }
    }
}
