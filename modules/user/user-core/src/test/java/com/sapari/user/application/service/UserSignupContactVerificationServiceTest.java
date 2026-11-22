package com.sapari.user.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sapari.user.application.port.VerificationCodeHasher;
import com.sapari.user.command.SignupContactVerificationConsumeCommand;
import com.sapari.user.domain.exception.UserErrorCode;
import com.sapari.user.domain.exception.UserException;
import com.sapari.user.domain.repository.SignupContactVerificationRepository;
import com.sapari.user.domain.repository.SignupContactVerificationRepository.ConsumeResult;

@ExtendWith(MockitoExtension.class)
@DisplayName("사용자 회원가입 연락처 인증 소비 서비스 테스트")
class UserSignupContactVerificationServiceTest {

    private static final String PHONE_NUMBER = "01012345678";
    private static final String EMAIL = "customer@example.com";
    private static final String PHONE_HASH = "phone-hash";
    private static final String EMAIL_HASH = "email-hash";

    @Mock
    private SignupContactVerificationRepository repository;

    @Mock
    private VerificationCodeHasher codeHasher;

    @Test
    @DisplayName("휴대폰과 이메일 verified가 모두 있으면 둘 다 함께 소비한다")
    void consumeSignupContactVerificationWhenBothVerifiedConsumesSuccessfully() {
        UserSignupContactVerificationService service = service();
        givenContactHashes();
        when(repository.consumeVerified(PHONE_HASH, EMAIL_HASH)).thenReturn(ConsumeResult.CONSUMED);

        service.consumeSignupContactVerification(new SignupContactVerificationConsumeCommand(PHONE_NUMBER, EMAIL));

        verify(repository).consumeVerified(PHONE_HASH, EMAIL_HASH);
    }

    @Test
    @DisplayName("휴대폰 verified가 없으면 휴대폰 인증 필요 예외를 던진다")
    void consumeSignupContactVerificationWhenPhoneMissingThrowsPhoneRequired() {
        UserSignupContactVerificationService service = service();
        givenContactHashes();
        when(repository.consumeVerified(PHONE_HASH, EMAIL_HASH)).thenReturn(ConsumeResult.PHONE_MISSING);

        assertThatThrownBy(() -> service.consumeSignupContactVerification(new SignupContactVerificationConsumeCommand(PHONE_NUMBER, EMAIL)))
                .isInstanceOfSatisfying(UserException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(UserErrorCode.SIGNUP_PHONE_VERIFICATION_REQUIRED)
                );
    }

    @Test
    @DisplayName("이메일 verified가 없으면 이메일 인증 필요 예외를 던진다")
    void consumeSignupContactVerificationWhenEmailMissingThrowsEmailRequired() {
        UserSignupContactVerificationService service = service();
        givenContactHashes();
        when(repository.consumeVerified(PHONE_HASH, EMAIL_HASH)).thenReturn(ConsumeResult.EMAIL_MISSING);

        assertThatThrownBy(() -> service.consumeSignupContactVerification(new SignupContactVerificationConsumeCommand(PHONE_NUMBER, EMAIL)))
                .isInstanceOfSatisfying(UserException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(UserErrorCode.SIGNUP_EMAIL_VERIFICATION_REQUIRED)
                );
    }

    private UserSignupContactVerificationService service() {
        return new UserSignupContactVerificationService(repository, codeHasher);
    }

    private void givenContactHashes() {
        when(codeHasher.hashPhoneNumber(PHONE_NUMBER)).thenReturn(PHONE_HASH);
        when(codeHasher.hashEmail(EMAIL)).thenReturn(EMAIL_HASH);
    }
}
