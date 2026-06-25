package com.sapari.notification.infrastructure.external.email;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import com.resend.Resend;
import com.resend.services.emails.model.CreateEmailOptions;
import com.resend.services.emails.model.CreateEmailResponse;
import com.resend.core.exception.ResendException;
import com.sapari.notification.application.port.EmailSender;
import com.sapari.notification.view.MessageSendResult;

/**
 * Resend SDK 기반 이메일 sender.
 * provider 예외에는 수신 이메일/본문/인증번호가 포함될 수 있으므로 로그에는 예외 타입만 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResendEmailSender implements EmailSender {

    private final Resend resend;
    private final ResendEmailProperties properties;

    /**
     * Resend provider로 이메일을 발송하되 실패 로그에는 수신자/본문/인증번호를 남기지 않는다.
     */
    @Override
    public MessageSendResult send(String email, String subject, String html) {
        CreateEmailOptions options = CreateEmailOptions.builder()
                .from(properties.from())
                .to(email)
                .subject(subject)
                .html(html)
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
