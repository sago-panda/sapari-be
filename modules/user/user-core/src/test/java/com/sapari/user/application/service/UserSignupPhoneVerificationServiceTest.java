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

import com.sapari.notification.command.SendSignupVerificationSmsCommand;
import com.sapari.notification.port.NotificationSendUseCase;
import com.sapari.notification.view.MessageSendResult;
import com.sapari.user.application.config.UserSignupPhoneVerificationProperties;
import com.sapari.user.application.port.VerificationCodeGenerator;
import com.sapari.user.application.port.VerificationCodeHasher;
import com.sapari.user.command.SignupPhoneVerificationConfirmCommand;
import com.sapari.user.command.SignupPhoneVerificationSendCommand;
import com.sapari.user.domain.exception.UserErrorCode;
import com.sapari.user.domain.exception.UserException;
import com.sapari.user.domain.repository.SignupPhoneVerificationRepository;
import com.sapari.user.domain.repository.UserRepository;
import com.sapari.user.view.SignupPhoneVerificationConfirmResult;
import com.sapari.user.view.SignupPhoneVerificationSendResult;

@ExtendWith(MockitoExtension.class)
@DisplayName("사용자 회원가입 휴대폰 인증 서비스 테스트")
public class UserSignupPhoneVerificationServiceTest {

    private static final String PHONE_NUMBER = "01012345678";
    private static final String PHONE_HASH = "phone-hash";
    private static final String CODE = "123456";
    private static final String CODE_HASH = "code-hash";

    @Mock
    private SignupPhoneVerificationRepository repository;

    @Mock
    private NotificationSendUseCase notificationSendUseCase;

    @Mock
    private VerificationCodeGenerator codeGenerator;

    @Mock
    private VerificationCodeHasher codeHasher;

    @Mock
    private UserRepository userRepository;

    private UserSignupPhoneVerificationProperties properties;
    private UserSignupPhoneVerificationService service;

    @BeforeEach
    void setUp() {
        properties = new UserSignupPhoneVerificationProperties();
        properties.setCodeTtl(Duration.ofMinutes(5));
        properties.setVerifiedTtl(Duration.ofMinutes(30));
        properties.setResendCooldown(Duration.ofSeconds(60));
        properties.setMaxAttempts(5);
        service = new UserSignupPhoneVerificationService(repository, notificationSendUseCase, codeGenerator, codeHasher, userRepository, properties);
    }

    @Test
    @DisplayName("이미 가입된 휴대폰 번호면 인증번호를 발송하지 않는다")
    void sendSignupPhoneVerificationWhenPhoneNumberDuplicatedThrowsDuplicatedPhoneNumber() {
        when(userRepository.existsByPhoneNumber(PHONE_NUMBER)).thenReturn(true);

        assertThatThrownBy(() -> service.sendSignupPhoneVerification(new SignupPhoneVerificationSendCommand(PHONE_NUMBER)))
                .isInstanceOfSatisfying(UserException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(UserErrorCode.DUPLICATED_PHONE_NUMBER)
                );

        verifyNoInteractions(repository, notificationSendUseCase, codeGenerator);
    }

    @Test
    @DisplayName("발송 성공 시 code를 저장하고 기존 실패 횟수를 삭제한다")
    void sendSignupPhoneVerificationWhenSendSucceedsSavesCodeAndDeletesFailures() {
        givenPhoneHash();
        when(userRepository.existsByPhoneNumber(PHONE_NUMBER)).thenReturn(false);
        when(repository.acquireCooldown(eq(PHONE_HASH), anyString(), eq(Duration.ofSeconds(60)))).thenReturn(true);
        when(codeGenerator.generateNumericCode(6)).thenReturn(CODE);
        when(notificationSendUseCase.sendSignupVerificationSms(any(SendSignupVerificationSmsCommand.class)))
                .thenReturn(new MessageSendResult(true, "message-id"));
        when(codeHasher.hashCode(PHONE_NUMBER, CODE)).thenReturn(CODE_HASH);

        SignupPhoneVerificationSendResult result = service.sendSignupPhoneVerification(new SignupPhoneVerificationSendCommand(PHONE_NUMBER));

        assertThat(result.sent()).isTrue();
        assertThat(result.expiresInSeconds()).isEqualTo(300L);
        assertThat(result.resendAvailableInSeconds()).isEqualTo(60L);
        ArgumentCaptor<SendSignupVerificationSmsCommand> commandCaptor =
                ArgumentCaptor.forClass(SendSignupVerificationSmsCommand.class);
        verify(notificationSendUseCase).sendSignupVerificationSms(commandCaptor.capture());
        assertThat(commandCaptor.getValue().phoneNumber()).isEqualTo(PHONE_NUMBER);
        assertThat(commandCaptor.getValue().verificationCode()).isEqualTo(CODE);
        verify(repository).saveCode(PHONE_HASH, CODE_HASH, Duration.ofMinutes(5));
        verify(repository).deleteFailures(PHONE_HASH);
        verify(repository, never()).releaseCooldown(eq(PHONE_HASH), anyString());
    }

    @Test
    @DisplayName("발송 실패 시 cooldown을 해제하고 code를 저장하지 않는다")
    void sendSignupPhoneVerificationWhenSendFailsReleasesCooldownAndDoesNotSaveCode() {
        givenPhoneHash();
        when(userRepository.existsByPhoneNumber(PHONE_NUMBER)).thenReturn(false);
        when(repository.acquireCooldown(eq(PHONE_HASH), anyString(), eq(Duration.ofSeconds(60)))).thenReturn(true);
        when(codeGenerator.generateNumericCode(6)).thenReturn(CODE);
        when(notificationSendUseCase.sendSignupVerificationSms(any(SendSignupVerificationSmsCommand.class)))
                .thenReturn(new MessageSendResult(false, null));

        assertThatThrownBy(() -> service.sendSignupPhoneVerification(new SignupPhoneVerificationSendCommand(PHONE_NUMBER)))
                .isInstanceOfSatisfying(UserException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(UserErrorCode.SIGNUP_VERIFICATION_SEND_UNAVAILABLE)
                );

        verify(repository).releaseCooldown(eq(PHONE_HASH), anyString());
        verify(repository, never()).saveCode(eq(PHONE_HASH), anyString(), eq(Duration.ofMinutes(5)));
    }

    @Test
    @DisplayName("인증번호가 일치하면 원자 confirm 결과에 따라 verified 응답을 반환한다")
    void confirmSignupPhoneVerificationWhenCodeMatchesReturnsVerified() {
        givenPhoneHash();
        when(codeHasher.hashCode(PHONE_NUMBER, CODE)).thenReturn(CODE_HASH);
        when(repository.confirmCode(PHONE_HASH, CODE_HASH, Duration.ofMinutes(5), Duration.ofMinutes(30), 5))
                .thenReturn(SignupPhoneVerificationRepository.ConfirmResult.VERIFIED);

        SignupPhoneVerificationConfirmResult result =
                service.confirmSignupPhoneVerification(new SignupPhoneVerificationConfirmCommand(PHONE_NUMBER, CODE));

        assertThat(result.phoneNumberVerified()).isTrue();
        assertThat(result.verifiedExpiresInSeconds()).isEqualTo(1800L);
        verify(repository).confirmCode(PHONE_HASH, CODE_HASH, Duration.ofMinutes(5), Duration.ofMinutes(30), 5);
    }

    @Test
    @DisplayName("저장된 인증번호가 없으면 인증번호 없음 예외를 던진다")
    void confirmSignupPhoneVerificationWhenCodeMissingThrowsCodeNotFound() {
        givenPhoneHash();
        when(codeHasher.hashCode(PHONE_NUMBER, CODE)).thenReturn(CODE_HASH);
        when(repository.confirmCode(PHONE_HASH, CODE_HASH, Duration.ofMinutes(5), Duration.ofMinutes(30), 5))
                .thenReturn(SignupPhoneVerificationRepository.ConfirmResult.CODE_NOT_FOUND);

        assertThatThrownBy(() -> service.confirmSignupPhoneVerification(new SignupPhoneVerificationConfirmCommand(PHONE_NUMBER, CODE)))
                .isInstanceOfSatisfying(UserException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(UserErrorCode.SIGNUP_PHONE_VERIFICATION_CODE_NOT_FOUND)
                );

        verify(repository).confirmCode(PHONE_HASH, CODE_HASH, Duration.ofMinutes(5), Duration.ofMinutes(30), 5);
    }

    @Test
    @DisplayName("인증번호가 불일치하면 원자 confirm 결과에 따라 불일치 예외를 던진다")
    void confirmSignupPhoneVerificationWhenCodeMismatchesThrowsMismatch() {
        givenPhoneHash();
        when(codeHasher.hashCode(PHONE_NUMBER, CODE)).thenReturn(CODE_HASH);
        when(repository.confirmCode(PHONE_HASH, CODE_HASH, Duration.ofMinutes(5), Duration.ofMinutes(30), 5))
                .thenReturn(SignupPhoneVerificationRepository.ConfirmResult.CODE_MISMATCH);

        assertThatThrownBy(() -> service.confirmSignupPhoneVerification(new SignupPhoneVerificationConfirmCommand(PHONE_NUMBER, CODE)))
                .isInstanceOfSatisfying(UserException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(UserErrorCode.SIGNUP_PHONE_VERIFICATION_CODE_MISMATCH)
                );

        verify(repository).confirmCode(PHONE_HASH, CODE_HASH, Duration.ofMinutes(5), Duration.ofMinutes(30), 5);
    }

    @Test
    @DisplayName("인증번호 실패 횟수가 최대치에 도달하면 초과 예외를 던진다")
    void confirmSignupPhoneVerificationWhenMaxAttemptsReachedThrowsAttemptsExceeded() {
        givenPhoneHash();
        when(codeHasher.hashCode(PHONE_NUMBER, CODE)).thenReturn(CODE_HASH);
        when(repository.confirmCode(PHONE_HASH, CODE_HASH, Duration.ofMinutes(5), Duration.ofMinutes(30), 5))
                .thenReturn(SignupPhoneVerificationRepository.ConfirmResult.ATTEMPTS_EXCEEDED);

        assertThatThrownBy(() -> service.confirmSignupPhoneVerification(new SignupPhoneVerificationConfirmCommand(PHONE_NUMBER, CODE)))
                .isInstanceOfSatisfying(UserException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(UserErrorCode.SIGNUP_PHONE_VERIFICATION_ATTEMPTS_EXCEEDED)
                );

        verify(repository).confirmCode(PHONE_HASH, CODE_HASH, Duration.ofMinutes(5), Duration.ofMinutes(30), 5);
    }

    @Test
    @DisplayName("회원가입 시 verified 상태가 있으면 한 번 소비하고 통과한다")
    void consumeSignupPhoneVerificationWhenVerifiedExistsConsumesSuccessfully() {
        givenPhoneHash();
        when(repository.consumeVerified(PHONE_HASH)).thenReturn(true);

        service.consumeSignupPhoneVerification(PHONE_NUMBER);

        verify(repository).consumeVerified(PHONE_HASH);
    }

    @Test
    @DisplayName("회원가입 시 verified 상태가 없으면 휴대폰 인증 필요 예외를 던진다")
    void consumeSignupPhoneVerificationWhenVerifiedMissingThrowsRequired() {
        givenPhoneHash();
        when(repository.consumeVerified(PHONE_HASH)).thenReturn(false);

        assertThatThrownBy(() -> service.consumeSignupPhoneVerification(PHONE_NUMBER))
                .isInstanceOfSatisfying(UserException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(UserErrorCode.SIGNUP_PHONE_VERIFICATION_REQUIRED)
                );
    }

    private void givenPhoneHash() {
        when(codeHasher.hashPhoneNumber(PHONE_NUMBER)).thenReturn(PHONE_HASH);
    }
}
