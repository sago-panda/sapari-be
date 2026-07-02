package com.sapari.user.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sapari.notification.command.SendSignupVerificationEmailCommand;
import com.sapari.notification.port.NotificationSendUseCase;
import com.sapari.notification.view.MessageSendResult;
import com.sapari.user.application.config.UserSignupEmailVerificationProperties;
import com.sapari.user.application.port.VerificationCodeGenerator;
import com.sapari.user.application.port.VerificationCodeHasher;
import com.sapari.user.command.SignupEmailVerificationConfirmCommand;
import com.sapari.user.command.SignupEmailVerificationSendCommand;
import com.sapari.user.domain.exception.UserErrorCode;
import com.sapari.user.domain.exception.UserException;
import com.sapari.user.domain.repository.SignupEmailVerificationRepository;
import com.sapari.user.domain.repository.UserRepository;
import com.sapari.user.view.SignupEmailVerificationConfirmResult;
import com.sapari.user.view.SignupEmailVerificationSendResult;

@ExtendWith(MockitoExtension.class)
@DisplayName("사용자 회원가입 이메일 인증 서비스 테스트")
class UserSignupEmailVerificationServiceTest {

    private static final String EMAIL = "user@example.com";
    private static final String EMAIL_HASH = "email-hash";
    private static final String CODE = "123456";
    private static final String CODE_HASH = "code-hash";

    @Mock
    private SignupEmailVerificationRepository repository;

    @Mock
    private NotificationSendUseCase notificationSendUseCase;

    @Mock
    private VerificationCodeGenerator codeGenerator;

    @Mock
    private VerificationCodeHasher codeHasher;

    @Mock
    private UserRepository userRepository;

    private UserSignupEmailVerificationProperties properties;
    private UserSignupEmailVerificationService service;

    @BeforeEach
    void setUp() {
        properties = new UserSignupEmailVerificationProperties();
        properties.setCodeTtl(Duration.ofMinutes(5));
        properties.setVerifiedTtl(Duration.ofMinutes(30));
        properties.setResendCooldown(Duration.ofSeconds(60));
        properties.setMaxAttempts(5);
        service = new UserSignupEmailVerificationService(repository, notificationSendUseCase, codeGenerator, codeHasher, userRepository, properties);
    }

    @Test
    @DisplayName("이미 가입된 이메일이면 인증번호를 발송하지 않는다")
    void sendSignupEmailVerificationWhenEmailDuplicatedThrowsDuplicatedEmail() {
        when(userRepository.existsByEmail(EMAIL)).thenReturn(true);

        assertThatThrownBy(() -> service.sendSignupEmailVerification(new SignupEmailVerificationSendCommand(EMAIL)))
                .isInstanceOfSatisfying(UserException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(UserErrorCode.DUPLICATED_EMAIL)
                );

        verifyNoInteractions(repository, notificationSendUseCase, codeGenerator);
    }

    @Test
    @DisplayName("발송 성공 시 code를 저장하고 기존 실패 횟수를 삭제한다")
    void sendSignupEmailVerificationWhenSendSucceedsSavesCodeAndDeletesFailures() {
        givenEmailHash();
        when(userRepository.existsByEmail(EMAIL)).thenReturn(false);
        when(repository.acquireCooldown(eq(EMAIL_HASH), anyString(), eq(Duration.ofSeconds(60)))).thenReturn(true);
        when(codeGenerator.generateNumericCode(6)).thenReturn(CODE);
        when(notificationSendUseCase.sendSignupVerificationEmail(any(SendSignupVerificationEmailCommand.class)))
                .thenReturn(new MessageSendResult(true, "message-id"));
        when(codeHasher.hashEmailCode(EMAIL, CODE)).thenReturn(CODE_HASH);

        SignupEmailVerificationSendResult result = service.sendSignupEmailVerification(new SignupEmailVerificationSendCommand(EMAIL));

        assertThat(result.sent()).isTrue();
        assertThat(result.expiresInSeconds()).isEqualTo(300L);
        assertThat(result.resendAvailableInSeconds()).isEqualTo(60L);
        ArgumentCaptor<SendSignupVerificationEmailCommand> commandCaptor =
                ArgumentCaptor.forClass(SendSignupVerificationEmailCommand.class);
        verify(notificationSendUseCase).sendSignupVerificationEmail(commandCaptor.capture());
        assertThat(commandCaptor.getValue().email()).isEqualTo(EMAIL);
        assertThat(commandCaptor.getValue().verificationCode()).isEqualTo(CODE);
        verify(repository).saveCode(EMAIL_HASH, CODE_HASH, Duration.ofMinutes(5));
        verify(repository).deleteFailures(EMAIL_HASH);
        verify(repository, never()).releaseCooldown(eq(EMAIL_HASH), anyString());
    }

    @Test
    @DisplayName("발송 실패 시 cooldown을 해제하고 code를 저장하지 않는다")
    void sendSignupEmailVerificationWhenSendFailsReleasesCooldownAndDoesNotSaveCode() {
        givenEmailHash();
        when(userRepository.existsByEmail(EMAIL)).thenReturn(false);
        when(repository.acquireCooldown(eq(EMAIL_HASH), anyString(), eq(Duration.ofSeconds(60)))).thenReturn(true);
        when(codeGenerator.generateNumericCode(6)).thenReturn(CODE);
        when(notificationSendUseCase.sendSignupVerificationEmail(any(SendSignupVerificationEmailCommand.class)))
                .thenReturn(new MessageSendResult(false, null));

        assertThatThrownBy(() -> service.sendSignupEmailVerification(new SignupEmailVerificationSendCommand(EMAIL)))
                .isInstanceOfSatisfying(UserException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(UserErrorCode.SIGNUP_VERIFICATION_SEND_UNAVAILABLE)
                );

        verify(repository).releaseCooldown(eq(EMAIL_HASH), anyString());
        verify(repository, never()).saveCode(eq(EMAIL_HASH), anyString(), eq(Duration.ofMinutes(5)));
    }

    @Test
    @DisplayName("인증번호가 일치하면 원자 confirm 결과에 따라 verified 응답을 반환한다")
    void confirmSignupEmailVerificationWhenCodeMatchesReturnsVerified() {
        givenEmailHash();
        when(codeHasher.hashEmailCode(EMAIL, CODE)).thenReturn(CODE_HASH);
        when(repository.confirmCode(EMAIL_HASH, CODE_HASH, Duration.ofMinutes(5), Duration.ofMinutes(30), 5))
                .thenReturn(SignupEmailVerificationRepository.ConfirmResult.VERIFIED);

        SignupEmailVerificationConfirmResult result =
                service.confirmSignupEmailVerification(new SignupEmailVerificationConfirmCommand(EMAIL, CODE));

        assertThat(result.emailVerified()).isTrue();
        assertThat(result.verifiedExpiresInSeconds()).isEqualTo(1800L);
        verify(repository).confirmCode(EMAIL_HASH, CODE_HASH, Duration.ofMinutes(5), Duration.ofMinutes(30), 5);
    }

    @Test
    @DisplayName("저장된 인증번호가 없으면 인증번호 없음 예외를 던진다")
    void confirmSignupEmailVerificationWhenCodeMissingThrowsCodeNotFound() {
        givenEmailHash();
        when(codeHasher.hashEmailCode(EMAIL, CODE)).thenReturn(CODE_HASH);
        when(repository.confirmCode(EMAIL_HASH, CODE_HASH, Duration.ofMinutes(5), Duration.ofMinutes(30), 5))
                .thenReturn(SignupEmailVerificationRepository.ConfirmResult.CODE_NOT_FOUND);

        assertThatThrownBy(() -> service.confirmSignupEmailVerification(new SignupEmailVerificationConfirmCommand(EMAIL, CODE)))
                .isInstanceOfSatisfying(UserException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(UserErrorCode.SIGNUP_EMAIL_VERIFICATION_CODE_NOT_FOUND)
                );

        verify(repository).confirmCode(EMAIL_HASH, CODE_HASH, Duration.ofMinutes(5), Duration.ofMinutes(30), 5);
    }

    @Test
    @DisplayName("인증번호가 불일치하면 원자 confirm 결과에 따라 불일치 예외를 던진다")
    void confirmSignupEmailVerificationWhenCodeMismatchesThrowsMismatch() {
        givenEmailHash();
        when(codeHasher.hashEmailCode(EMAIL, CODE)).thenReturn(CODE_HASH);
        when(repository.confirmCode(EMAIL_HASH, CODE_HASH, Duration.ofMinutes(5), Duration.ofMinutes(30), 5))
                .thenReturn(SignupEmailVerificationRepository.ConfirmResult.CODE_MISMATCH);

        assertThatThrownBy(() -> service.confirmSignupEmailVerification(new SignupEmailVerificationConfirmCommand(EMAIL, CODE)))
                .isInstanceOfSatisfying(UserException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(UserErrorCode.SIGNUP_EMAIL_VERIFICATION_CODE_MISMATCH)
                );

        verify(repository).confirmCode(EMAIL_HASH, CODE_HASH, Duration.ofMinutes(5), Duration.ofMinutes(30), 5);
    }

    @Test
    @DisplayName("인증번호 실패 횟수가 최대치에 도달하면 초과 예외를 던진다")
    void confirmSignupEmailVerificationWhenMaxAttemptsReachedThrowsAttemptsExceeded() {
        givenEmailHash();
        when(codeHasher.hashEmailCode(EMAIL, CODE)).thenReturn(CODE_HASH);
        when(repository.confirmCode(EMAIL_HASH, CODE_HASH, Duration.ofMinutes(5), Duration.ofMinutes(30), 5))
                .thenReturn(SignupEmailVerificationRepository.ConfirmResult.ATTEMPTS_EXCEEDED);

        assertThatThrownBy(() -> service.confirmSignupEmailVerification(new SignupEmailVerificationConfirmCommand(EMAIL, CODE)))
                .isInstanceOfSatisfying(UserException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(UserErrorCode.SIGNUP_EMAIL_VERIFICATION_ATTEMPTS_EXCEEDED)
                );

        verify(repository).confirmCode(EMAIL_HASH, CODE_HASH, Duration.ofMinutes(5), Duration.ofMinutes(30), 5);
    }

    @Test
    @DisplayName("회원가입 시 verified 상태가 없으면 이메일 인증 필요 예외를 던진다")
    void consumeSignupEmailVerificationWhenVerifiedMissingThrowsRequired() {
        givenEmailHash();
        when(repository.consumeVerified(EMAIL_HASH)).thenReturn(false);

        assertThatThrownBy(() -> service.consumeSignupEmailVerification(EMAIL))
                .isInstanceOfSatisfying(UserException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(UserErrorCode.SIGNUP_EMAIL_VERIFICATION_REQUIRED)
                );
    }

    private void givenEmailHash() {
        when(codeHasher.hashEmail(EMAIL)).thenReturn(EMAIL_HASH);
    }
}
