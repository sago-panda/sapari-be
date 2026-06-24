package com.sapari.customer.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sapari.customer.application.config.CustomerPhoneVerificationProperties;
import com.sapari.customer.application.port.SmsSendResult;
import com.sapari.customer.application.port.SmsSender;
import com.sapari.customer.command.CustomerPhoneVerificationConfirmCommand;
import com.sapari.customer.command.CustomerPhoneVerificationSendCommand;
import com.sapari.customer.domain.exception.CustomerErrorCode;
import com.sapari.customer.domain.exception.CustomerException;
import com.sapari.customer.domain.repository.CustomerPhoneVerificationRepository;
import com.sapari.customer.view.CustomerPhoneVerificationConfirmResult;
import com.sapari.customer.view.CustomerPhoneVerificationSendResult;

@ExtendWith(MockitoExtension.class)
@DisplayName("구매자 휴대폰 인증 서비스 테스트")
class CustomerPhoneVerificationServiceTest {

    private static final String PHONE_NUMBER = "01012345678";
    private static final String PHONE_HASH = "phone-hash";
    private static final String CODE = "123456";
    private static final String CODE_HASH = "code-hash";

    @Mock
    private CustomerPhoneVerificationRepository repository;

    @Mock
    private SmsSender smsSender;

    @Mock
    private VerificationCodeGenerator codeGenerator;

    @Mock
    private VerificationCodeHasher codeHasher;

    private CustomerPhoneVerificationProperties properties;
    private CustomerPhoneVerificationService service;

    @BeforeEach
    void setUp() {
        properties = new CustomerPhoneVerificationProperties();
        properties.setCodeTtl(Duration.ofMinutes(5));
        properties.setVerifiedTtl(Duration.ofMinutes(10));
        properties.setResendCooldown(Duration.ofSeconds(60));
        properties.setMaxAttempts(5);
        service = new CustomerPhoneVerificationService(repository, smsSender, codeGenerator, codeHasher, properties);
    }

    @Test
    @DisplayName("쿨다운 선점에 실패하면 인증번호를 발송하지 않는다")
    void sendSignupCodeWhenCooldownCannotBeAcquiredThrowsCooldownException() {
        givenPhoneHash();
        when(repository.acquireCooldown(eq(PHONE_HASH), anyString(), eq(Duration.ofSeconds(60)))).thenReturn(false);

        assertThatThrownBy(() -> service.sendSignupCode(new CustomerPhoneVerificationSendCommand(PHONE_NUMBER)))
                .isInstanceOfSatisfying(CustomerException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CustomerErrorCode.PHONE_VERIFICATION_COOLDOWN)
                );

        verifyNoInteractions(codeGenerator, smsSender);
        verify(repository, never()).releaseCooldown(any(), any());
        verify(repository, never()).saveCode(any(), any(), any());
        verify(repository, never()).deleteFailures(any());
    }

    @Test
    @DisplayName("쿨다운 선점 후 문자 발송 성공 시 code를 저장하고 기존 실패 횟수를 리셋한다")
    void sendSignupCodeAcquiresCooldownGeneratesCodeSendsSmsSavesCodeAndDeletesFailures() {
        givenPhoneHash();
        when(repository.acquireCooldown(eq(PHONE_HASH), anyString(), eq(Duration.ofSeconds(60)))).thenReturn(true);
        when(codeGenerator.generateNumericCode(6)).thenReturn(CODE);
        when(smsSender.sendVerificationCode(PHONE_NUMBER, CODE)).thenReturn(new SmsSendResult(true, "message-id"));
        when(codeHasher.hashCode(PHONE_NUMBER, CODE)).thenReturn(CODE_HASH);

        CustomerPhoneVerificationSendResult result = service.sendSignupCode(new CustomerPhoneVerificationSendCommand(PHONE_NUMBER));

        assertThat(result.sent()).isTrue();
        assertThat(result.expiresInSeconds()).isEqualTo(300L);
        assertThat(result.resendAvailableInSeconds()).isEqualTo(60L);
        verify(repository).saveCode(PHONE_HASH, CODE_HASH, Duration.ofMinutes(5));
        verify(repository).deleteFailures(PHONE_HASH);
        verify(repository, never()).releaseCooldown(eq(PHONE_HASH), anyString());
    }

    @Test
    @DisplayName("문자 발송 실패 시 선점한 쿨다운을 해제하고 code를 저장하지 않는다")
    void sendSignupCodeWhenSmsFailsReleasesCooldownAndDoesNotSaveCode() {
        givenPhoneHash();
        when(repository.acquireCooldown(eq(PHONE_HASH), anyString(), eq(Duration.ofSeconds(60)))).thenReturn(true);
        when(codeGenerator.generateNumericCode(6)).thenReturn(CODE);
        when(smsSender.sendVerificationCode(PHONE_NUMBER, CODE)).thenReturn(new SmsSendResult(false, null));

        assertThatThrownBy(() -> service.sendSignupCode(new CustomerPhoneVerificationSendCommand(PHONE_NUMBER)))
                .isInstanceOfSatisfying(CustomerException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CustomerErrorCode.SMS_SEND_UNAVAILABLE)
                );

        verify(repository).releaseCooldown(eq(PHONE_HASH), anyString());
        verify(repository, never()).saveCode(any(), any(), any());
        verify(repository, never()).deleteFailures(any());
    }

    @Test
    @DisplayName("문자 발송 중 예상하지 못한 런타임 예외가 발생해도 쿨다운을 해제한다")
    void sendSignupCodeWhenSmsSenderThrowsRuntimeExceptionReleasesCooldown() {
        givenPhoneHash();
        when(repository.acquireCooldown(eq(PHONE_HASH), anyString(), eq(Duration.ofSeconds(60)))).thenReturn(true);
        when(codeGenerator.generateNumericCode(6)).thenReturn(CODE);
        when(smsSender.sendVerificationCode(PHONE_NUMBER, CODE)).thenThrow(new IllegalStateException("provider failure"));

        assertThatThrownBy(() -> service.sendSignupCode(new CustomerPhoneVerificationSendCommand(PHONE_NUMBER)))
                .isInstanceOfSatisfying(CustomerException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CustomerErrorCode.SMS_SEND_UNAVAILABLE)
                );

        verify(repository).releaseCooldown(eq(PHONE_HASH), anyString());
        verify(repository, never()).saveCode(any(), any(), any());
        verify(repository, never()).deleteFailures(any());
    }

    @Test
    @DisplayName("저장된 인증번호가 없으면 만료 예외를 던진다")
    void confirmSignupCodeWhenCodeMissingThrowsCodeNotFound() {
        givenPhoneHash();
        when(repository.findCodeHash(PHONE_HASH)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirmSignupCode(new CustomerPhoneVerificationConfirmCommand(PHONE_NUMBER, CODE)))
                .isInstanceOfSatisfying(CustomerException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CustomerErrorCode.PHONE_VERIFICATION_CODE_NOT_FOUND)
                );
    }

    @Test
    @DisplayName("인증번호가 불일치하면 실패 횟수를 증가시킨다")
    void confirmSignupCodeWhenCodeMismatchIncrementsFailure() {
        givenPhoneHash();
        when(repository.findCodeHash(PHONE_HASH)).thenReturn(Optional.of("stored-code-hash"));
        when(codeHasher.hashCode(PHONE_NUMBER, CODE)).thenReturn(CODE_HASH);
        when(repository.incrementFailure(PHONE_HASH, Duration.ofMinutes(5))).thenReturn(1L);

        assertThatThrownBy(() -> service.confirmSignupCode(new CustomerPhoneVerificationConfirmCommand(PHONE_NUMBER, CODE)))
                .isInstanceOfSatisfying(CustomerException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CustomerErrorCode.PHONE_VERIFICATION_CODE_MISMATCH)
                );
    }

    @Test
    @DisplayName("5회 불일치하면 기존 인증번호와 실패 횟수를 폐기한다")
    void confirmSignupCodeWhenFifthMismatchDeletesCodeAndFailures() {
        givenPhoneHash();
        when(repository.findCodeHash(PHONE_HASH)).thenReturn(Optional.of("stored-code-hash"));
        when(codeHasher.hashCode(PHONE_NUMBER, CODE)).thenReturn(CODE_HASH);
        when(repository.incrementFailure(PHONE_HASH, Duration.ofMinutes(5))).thenReturn(5L);

        assertThatThrownBy(() -> service.confirmSignupCode(new CustomerPhoneVerificationConfirmCommand(PHONE_NUMBER, CODE)))
                .isInstanceOfSatisfying(CustomerException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CustomerErrorCode.PHONE_VERIFICATION_ATTEMPTS_EXCEEDED)
                );

        verify(repository).deleteCodeAndFailures(PHONE_HASH);
    }

    @Test
    @DisplayName("인증번호가 일치하면 code와 실패 횟수를 삭제하고 verified 상태를 저장한다")
    void confirmSignupCodeWhenCodeMatchesDeletesCodeAndFailuresAndSavesVerified() {
        givenPhoneHash();
        when(repository.findCodeHash(PHONE_HASH)).thenReturn(Optional.of(CODE_HASH));
        when(codeHasher.hashCode(PHONE_NUMBER, CODE)).thenReturn(CODE_HASH);

        CustomerPhoneVerificationConfirmResult result =
                service.confirmSignupCode(new CustomerPhoneVerificationConfirmCommand(PHONE_NUMBER, CODE));

        assertThat(result.phoneNumberVerified()).isTrue();
        assertThat(result.verifiedExpiresInSeconds()).isEqualTo(600L);
        verify(repository).deleteCodeAndFailures(PHONE_HASH);
        verify(repository).saveVerified(PHONE_HASH, Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("회원가입 시 verified 상태가 없으면 휴대폰 인증 필요 예외를 던진다")
    void consumeSignupVerificationWhenVerifiedMissingThrowsRequired() {
        givenPhoneHash();
        when(repository.consumeVerified(PHONE_HASH)).thenReturn(false);

        assertThatThrownBy(() -> service.consumeSignupVerification(PHONE_NUMBER))
                .isInstanceOfSatisfying(CustomerException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CustomerErrorCode.PHONE_VERIFICATION_REQUIRED)
                );
    }

    @Test
    @DisplayName("회원가입 시 verified 상태가 있으면 한 번 소비한다")
    void consumeSignupVerificationWhenVerifiedExistsConsumesOnce() {
        givenPhoneHash();
        when(repository.consumeVerified(PHONE_HASH)).thenReturn(true);

        service.consumeSignupVerification(PHONE_NUMBER);

        verify(repository).consumeVerified(PHONE_HASH);
    }

    private void givenPhoneHash() {
        when(codeHasher.hashPhoneNumber(PHONE_NUMBER)).thenReturn(PHONE_HASH);
    }
}
