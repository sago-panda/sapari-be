package com.sapari.notification.infrastructure.external.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.Emails;
import com.resend.services.emails.model.CreateEmailOptions;
import com.resend.services.emails.model.CreateEmailResponse;
import com.sapari.notification.view.MessageSendResult;

@DisplayName("Resend 이메일 sender 테스트")
class ResendEmailSenderTest {

    private final Resend resend = mock(Resend.class);
    private final Emails emails = mock(Emails.class);
    private final ResendEmailProperties properties = new ResendEmailProperties(
            "Sapari <noreply@example.com>",
            "template_signup_verification",
            new ResendEmailProperties.Resend("re_test")
    );
    private final ResendEmailSender sender = new ResendEmailSender(resend, properties);

    @Test
    @DisplayName("회원가입 인증 이메일은 Resend template id와 variables로 발송한다")
    void sendSignupVerificationUsesResendTemplate() throws ResendException {
        when(resend.emails()).thenReturn(emails);
        when(emails.send(any(CreateEmailOptions.class))).thenReturn(new CreateEmailResponse("email-id"));

        MessageSendResult result = sender.sendSignupVerification("user@example.com", "123456");

        ArgumentCaptor<CreateEmailOptions> optionsCaptor = ArgumentCaptor.forClass(CreateEmailOptions.class);
        verify(emails).send(optionsCaptor.capture());

        CreateEmailOptions options = optionsCaptor.getValue();
        assertThat(result).isEqualTo(new MessageSendResult(true, "email-id"));
        assertThat(options.getFrom()).isEqualTo("Sapari <noreply@example.com>");
        assertThat(options.getTo()).containsExactly("user@example.com");
        assertThat(options.getSubject()).isNull();
        assertThat(options.getHtml()).isNull();
        assertThat(options.getTemplate().getId()).isEqualTo("template_signup_verification");
        assertThat(options.getTemplate().getVariables())
                .containsEntry("account", "user@example.com")
                .containsEntry("code", "123456");
    }

    @Test
    @DisplayName("Resend 예외가 발생하면 실패 결과를 반환한다")
    void sendSignupVerificationReturnsFailureWhenResendThrows() throws ResendException {
        when(resend.emails()).thenReturn(emails);
        when(emails.send(any(CreateEmailOptions.class))).thenThrow(new ResendException("failed"));

        MessageSendResult result = sender.sendSignupVerification("user@example.com", "123456");

        assertThat(result).isEqualTo(new MessageSendResult(false, null));
    }
}
