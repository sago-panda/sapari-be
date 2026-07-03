package com.sapari.customer.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import tools.jackson.databind.ObjectMapper;

import com.sapari.common.core.exception.BusinessException;
import com.sapari.common.core.exception.ErrorCode;
import com.sapari.common.securityjwt.jwt.JwtProperties;
import com.sapari.common.securityjwt.jwt.JwtSubject;
import com.sapari.common.securityjwt.jwt.JwtTokenClaims;
import com.sapari.common.securityjwt.jwt.JwtTokenLifecycle;
import com.sapari.common.securityjwt.jwt.JwtTokenProvider;
import com.sapari.common.securityjwt.jwt.JwtTokenType;
import com.sapari.global.time.TimeProvider;
import com.sapari.customer.application.dto.SocialSignupInfo;
import com.sapari.customer.command.CustomerLogoutCommand;
import com.sapari.customer.command.CustomerNicknameUpdateCommand;
import com.sapari.customer.command.CustomerPhoneVerificationConfirmCommand;
import com.sapari.customer.command.CustomerPhoneVerificationSendCommand;
import com.sapari.customer.command.SocialSignupCommand;
import com.sapari.customer.domain.exception.CustomerErrorCode;
import com.sapari.customer.domain.exception.CustomerException;
import com.sapari.customer.view.CustomerMeView;
import com.sapari.customer.view.CustomerNicknameUpdateResult;
import com.sapari.customer.view.CustomerPhoneVerificationConfirmResult;
import com.sapari.customer.view.CustomerPhoneVerificationSendResult;
import com.sapari.customer.view.CustomerTokenReissueResult;
import com.sapari.customer.view.SocialSignupInfoView;
import com.sapari.customer.view.SocialLoginTokenResult;
import com.sapari.customer.view.SocialSignupResult;
import com.sapari.customer.domain.repository.SocialLoginCodeRepository;
import com.sapari.customer.domain.repository.SocialSignupRepository;
import com.sapari.user.command.RegisterSocialCustomerCommand;
import com.sapari.user.command.SignupContactVerificationConsumeCommand;
import com.sapari.user.command.SignupPhoneVerificationConfirmCommand;
import com.sapari.user.command.SignupPhoneVerificationSendCommand;
import com.sapari.common.securityjwt.store.AccessTokenBlacklist;
import com.sapari.common.securityjwt.store.RefreshTokenStore;
import com.sapari.common.securityjwt.store.SessionRevocationStore;
import com.sapari.customer.application.mapper.CustomerViewMapper;
import com.sapari.user.model.ProviderType;
import com.sapari.user.model.UserGender;
import com.sapari.user.model.UserGrade;
import com.sapari.user.model.UserRole;
import com.sapari.user.model.UserStatus;
import com.sapari.user.port.UserAccountUseCase;
import com.sapari.user.port.UserSignupContactVerificationUseCase;
import com.sapari.user.port.UserSignupEmailVerificationUseCase;
import com.sapari.user.port.UserSignupPhoneVerificationUseCase;
import com.sapari.user.view.SignupPhoneVerificationConfirmResult;
import com.sapari.user.view.SignupPhoneVerificationSendResult;
import com.sapari.user.view.UserView;

@DisplayName("구매자 인증 서비스 테스트")
class CustomerAuthServiceTest {

    private static final String SECRET = "test-secret-key-for-customer-jwt-32bytes";
    private static final String SIGNUP_SID = "signup-session-id";
    private static final String TEMPORARY_LOGIN_CODE = "temporary-login-code";
    private static final String EMAIL = "customer@example.com";
    private static final Instant NOW = Instant.now();

    private final SocialSignupRepository socialSignupRepository =
            mock(SocialSignupRepository.class);
    private final SocialLoginCodeRepository socialLoginCodeRepository =
            mock(SocialLoginCodeRepository.class);
    private final UserAccountUseCase userAccountUseCase = mock(UserAccountUseCase.class);
    private final JwtTokenProvider jwtTokenProvider = new JwtTokenProvider(
            new JwtProperties("customer-test", SECRET, 3600L, 1209600L),
            timeProvider()
    );
    private final RefreshTokenStore refreshTokenStore =
            mock(RefreshTokenStore.class);
    private final SessionRevocationStore sessionRevocationStore =
            mock(SessionRevocationStore.class);
    private final AccessTokenBlacklist accessTokenBlacklist =
            mock(AccessTokenBlacklist.class);
    private final UserSignupPhoneVerificationUseCase userSignupPhoneVerificationUseCase =
            mock(UserSignupPhoneVerificationUseCase.class);
    private final UserSignupEmailVerificationUseCase userSignupEmailVerificationUseCase =
            mock(UserSignupEmailVerificationUseCase.class);
    private final UserSignupContactVerificationUseCase userSignupContactVerificationUseCase =
            mock(UserSignupContactVerificationUseCase.class);
    private final CustomerSignupContactVerificationAdapter signupContactVerificationAdapter =
            new CustomerSignupContactVerificationAdapter(
                    userSignupPhoneVerificationUseCase,
                    userSignupEmailVerificationUseCase,
                    userSignupContactVerificationUseCase
            );
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JwtTokenLifecycle jwtTokenLifecycle = new JwtTokenLifecycle(
            jwtTokenProvider,
            refreshTokenStore,
            sessionRevocationStore,
            accessTokenBlacklist,
            timeProvider()
    );
    private final CustomerJwtTokenAdapter customerJwtTokenAdapter =
            new CustomerJwtTokenAdapter(jwtTokenLifecycle);
    private final CustomerAuthService customerAuthService = new CustomerAuthService(
            socialSignupRepository,
            socialLoginCodeRepository,
            userAccountUseCase,
            customerJwtTokenAdapter,
            timeProvider(),
            objectMapper,
            Mappers.getMapper(CustomerViewMapper.class),
            signupContactVerificationAdapter
    );

    @Test
    @DisplayName("소셜 고객 가입 완료 시 User를 저장하고 토큰을 발급한다")
    void completeSocialSignupCreatesUserAndIssuesTokens() throws Exception {
        // given
        UUID userId = UUID.randomUUID();
        when(socialSignupRepository.findBySid(SIGNUP_SID))
                .thenReturn(Optional.of(objectMapper.writeValueAsString(socialSignupInfo())));
        when(userAccountUseCase.registerSocialCustomer(any(RegisterSocialCustomerCommand.class)))
                .thenReturn(customerView(userId));

        // when
        SocialSignupResult result = customerAuthService.completeSocialSignup(SIGNUP_SID, signupCommand());

        // then
        assertThat(result.userId()).isEqualTo(userId);
        JwtTokenClaims accessClaims = jwtTokenProvider.parseToken(result.accessToken());
        JwtTokenClaims refreshClaims = jwtTokenProvider.parseToken(result.refreshToken());
        assertThat(accessClaims.tokenType()).isEqualTo(JwtTokenType.ACCESS);
        assertThat(accessClaims.nickname()).isEqualTo("customer");
        assertThat(accessClaims.email()).isEqualTo(EMAIL);
        assertThat(refreshClaims.tokenType()).isEqualTo(JwtTokenType.REFRESH);
        assertThat(refreshClaims.sessionId()).isEqualTo(accessClaims.sessionId());
        assertThat(refreshClaims.tokenId()).isNotEqualTo(accessClaims.tokenId());
        assertThat(refreshClaims.nickname()).isNull();
        assertThat(refreshClaims.email()).isNull();

        ArgumentCaptor<RegisterSocialCustomerCommand> commandCaptor =
                ArgumentCaptor.forClass(RegisterSocialCustomerCommand.class);
        verify(userAccountUseCase).registerSocialCustomer(commandCaptor.capture());
        assertThat(commandCaptor.getValue().provider()).isEqualTo(ProviderType.NAVER);
        assertThat(commandCaptor.getValue().providerId()).isEqualTo("naver-id");
        assertThat(commandCaptor.getValue().email()).isEqualTo(EMAIL);
        assertThat(commandCaptor.getValue().gender()).isEqualTo(UserGender.FEMALE);
        assertThat(commandCaptor.getValue().profileImageUrl()).isEqualTo("https://image.example/request-profile.png");
        assertThat(commandCaptor.getValue().privacyAgreed()).isTrue();
        assertThat(commandCaptor.getValue().marketingAgreed()).isTrue();

        verify(userSignupContactVerificationUseCase).consumeSignupContactVerification(
                new SignupContactVerificationConsumeCommand("01012345678", EMAIL)
        );
        verify(userSignupPhoneVerificationUseCase, never()).consumeSignupPhoneVerification(any());
        verify(userSignupEmailVerificationUseCase, never()).consumeSignupEmailVerification(any());
        verify(socialSignupRepository).delete(SIGNUP_SID);
        verify(refreshTokenStore).save(
                eq(userId),
                eq(refreshClaims.sessionId()),
                eq(refreshClaims.tokenId()),
                any(Duration.class)
        );
    }


    @Test
    @DisplayName("휴대폰·이메일 인증 소비 후 가입 저장이 실패하면 재인증이 필요하다")
    void completeSocialSignupConsumesVerificationBeforeRegisterFailure() throws Exception {
        when(socialSignupRepository.findBySid(SIGNUP_SID))
                .thenReturn(Optional.of(objectMapper.writeValueAsString(socialSignupInfo())));
        when(userAccountUseCase.registerSocialCustomer(any(RegisterSocialCustomerCommand.class)))
                .thenThrow(new DataIntegrityViolationException("duplicated"));

        assertThatThrownBy(() -> customerAuthService.completeSocialSignup(SIGNUP_SID, signupCommand()))
                .isInstanceOfSatisfying(CustomerException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CustomerErrorCode.DUPLICATED_SIGNUP_INFO)
                );

        verify(userSignupContactVerificationUseCase).consumeSignupContactVerification(
                new SignupContactVerificationConsumeCommand("01012345678", EMAIL)
        );
        verify(socialSignupRepository, never()).delete(SIGNUP_SID);
    }

    @Test
    @DisplayName("휴대폰 인증이 완료되지 않으면 CUSTOMER 인증 필요 예외로 매핑하고 소셜 고객 가입을 저장하지 않는다")
    void completeSocialSignupRequiresPhoneVerification() throws Exception {
        when(socialSignupRepository.findBySid(SIGNUP_SID))
                .thenReturn(Optional.of(objectMapper.writeValueAsString(socialSignupInfo())));
        TestUserException verificationRequired = new TestUserException(TestUserErrorCode.SIGNUP_PHONE_VERIFICATION_REQUIRED);
        doThrow(verificationRequired)
                .when(userSignupContactVerificationUseCase)
                .consumeSignupContactVerification(new SignupContactVerificationConsumeCommand("01012345678", EMAIL));

        assertThatThrownBy(() -> customerAuthService.completeSocialSignup(SIGNUP_SID, signupCommand()))
                .isInstanceOfSatisfying(CustomerException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(CustomerErrorCode.PHONE_VERIFICATION_REQUIRED);
                    assertThat(exception).hasCause(verificationRequired);
                });

        verify(userSignupContactVerificationUseCase).consumeSignupContactVerification(
                new SignupContactVerificationConsumeCommand("01012345678", EMAIL)
        );
        verifyNoInteractions(userAccountUseCase);
    }

    @Test
    @DisplayName("이메일 인증이 완료되지 않으면 CUSTOMER 이메일 인증 필요 예외로 매핑하고 휴대폰 인증 상태는 보존한다")
    void completeSocialSignupRequiresEmailVerification() throws Exception {
        when(socialSignupRepository.findBySid(SIGNUP_SID))
                .thenReturn(Optional.of(objectMapper.writeValueAsString(socialSignupInfo())));
        TestUserException verificationRequired = new TestUserException(TestUserErrorCode.SIGNUP_EMAIL_VERIFICATION_REQUIRED);
        doThrow(verificationRequired)
                .when(userSignupContactVerificationUseCase)
                .consumeSignupContactVerification(new SignupContactVerificationConsumeCommand("01012345678", EMAIL));

        assertThatThrownBy(() -> customerAuthService.completeSocialSignup(SIGNUP_SID, signupCommand()))
                .isInstanceOfSatisfying(CustomerException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(CustomerErrorCode.EMAIL_VERIFICATION_REQUIRED);
                    assertThat(exception).hasCause(verificationRequired);
                });

        verify(userSignupContactVerificationUseCase).consumeSignupContactVerification(
                new SignupContactVerificationConsumeCommand("01012345678", EMAIL)
        );
        verify(userSignupPhoneVerificationUseCase, never()).consumeSignupPhoneVerification(any());
        verify(userSignupEmailVerificationUseCase, never()).consumeSignupEmailVerification(any());
        verifyNoInteractions(userAccountUseCase);
    }

    @Test
    @DisplayName("회원가입 연락처 인증 소비 중 알 수 없는 USER 오류는 CUSTOMER 일반 인증 오류로 매핑한다")
    void completeSocialSignupWhenUnknownUserVerificationErrorMapsGenericCustomerException() throws Exception {
        when(socialSignupRepository.findBySid(SIGNUP_SID))
                .thenReturn(Optional.of(objectMapper.writeValueAsString(socialSignupInfo())));
        TestUserException unknownUserError = new TestUserException(TestUserErrorCode.UNKNOWN_USER_ERROR);
        doThrow(unknownUserError)
                .when(userSignupContactVerificationUseCase)
                .consumeSignupContactVerification(new SignupContactVerificationConsumeCommand("01012345678", EMAIL));

        assertThatThrownBy(() -> customerAuthService.completeSocialSignup(SIGNUP_SID, signupCommand()))
                .isInstanceOfSatisfying(CustomerException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(CustomerErrorCode.SIGNUP_VERIFICATION_UNAVAILABLE);
                    assertThat(exception).hasCause(unknownUserError);
                });

        verifyNoInteractions(userAccountUseCase);
    }

    @Test
    @DisplayName("가입 sid가 없으면 소셜 고객 가입에 실패한다")
    void completeSocialSignupThrowsExceptionWhenSignupSidIsMissing() {
        assertThatThrownBy(() -> customerAuthService.completeSocialSignup(null, signupCommand()))
                .isInstanceOfSatisfying(CustomerException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CustomerErrorCode.INVALID_SIGNUP_SESSION)
                );

        verifyNoInteractions(userAccountUseCase, refreshTokenStore);
    }

    @Test
    @DisplayName("이미 가입된 휴대폰 번호는 CUSTOMER 중복 전화번호 예외로 매핑한다")
    void sendSignupPhoneVerificationWhenPhoneNumberDuplicatedThrowsDuplicatedPhoneNumber() {
        CustomerPhoneVerificationSendCommand command = new CustomerPhoneVerificationSendCommand("01012345678");
        SignupPhoneVerificationSendCommand userCommand = new SignupPhoneVerificationSendCommand("01012345678");
        TestUserException duplicatedPhoneNumber = new TestUserException(TestUserErrorCode.DUPLICATED_PHONE_NUMBER);
        doThrow(duplicatedPhoneNumber)
                .when(userSignupPhoneVerificationUseCase).sendSignupPhoneVerification(userCommand);

        assertThatThrownBy(() -> customerAuthService.sendSignupPhoneVerification(command))
                .isInstanceOfSatisfying(CustomerException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(CustomerErrorCode.DUPLICATED_PHONE_NUMBER);
                    assertThat(exception).hasCause(duplicatedPhoneNumber);
                });

        verifyNoInteractions(userAccountUseCase);
    }

    @Test
    @DisplayName("미가입 휴대폰 번호는 user 인증 use case로 발송을 위임한다")
    void sendSignupPhoneVerificationWhenPhoneNumberAvailableSendsCode() {
        CustomerPhoneVerificationSendCommand command = new CustomerPhoneVerificationSendCommand("01012345678");
        SignupPhoneVerificationSendCommand userCommand = new SignupPhoneVerificationSendCommand("01012345678");
        SignupPhoneVerificationSendResult sendResult = new SignupPhoneVerificationSendResult(true, 300L, 60L);
        when(userSignupPhoneVerificationUseCase.sendSignupPhoneVerification(userCommand)).thenReturn(sendResult);

        CustomerPhoneVerificationSendResult result = customerAuthService.sendSignupPhoneVerification(command);

        assertThat(result.sent()).isTrue();
        assertThat(result.expiresInSeconds()).isEqualTo(300L);
        assertThat(result.resendAvailableInSeconds()).isEqualTo(60L);
        verify(userSignupPhoneVerificationUseCase).sendSignupPhoneVerification(userCommand);
        verifyNoInteractions(userAccountUseCase);
    }

    @Test
    @DisplayName("인증번호 불일치는 CUSTOMER 인증번호 불일치 예외로 매핑한다")
    void confirmSignupPhoneVerificationWhenCodeMismatchesMapsCustomerException() {
        CustomerPhoneVerificationConfirmCommand command = new CustomerPhoneVerificationConfirmCommand("01012345678", "000000");
        SignupPhoneVerificationConfirmCommand userCommand = new SignupPhoneVerificationConfirmCommand("01012345678", "000000");
        TestUserException codeMismatch = new TestUserException(TestUserErrorCode.SIGNUP_PHONE_VERIFICATION_CODE_MISMATCH);
        doThrow(codeMismatch)
                .when(userSignupPhoneVerificationUseCase).confirmSignupPhoneVerification(userCommand);

        assertThatThrownBy(() -> customerAuthService.confirmSignupPhoneVerification(command))
                .isInstanceOfSatisfying(CustomerException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(CustomerErrorCode.PHONE_VERIFICATION_CODE_MISMATCH);
                    assertThat(exception).hasCause(codeMismatch);
                });
    }

    @Test
    @DisplayName("휴대폰 인증 확인 중 알 수 없는 USER 오류는 CUSTOMER 일반 인증 오류로 매핑한다")
    void confirmSignupPhoneVerificationWhenUnknownUserErrorMapsGenericCustomerException() {
        CustomerPhoneVerificationConfirmCommand command = new CustomerPhoneVerificationConfirmCommand("01012345678", "000000");
        SignupPhoneVerificationConfirmCommand userCommand = new SignupPhoneVerificationConfirmCommand("01012345678", "000000");
        TestUserException unknownUserError = new TestUserException(TestUserErrorCode.UNKNOWN_USER_ERROR);
        doThrow(unknownUserError)
                .when(userSignupPhoneVerificationUseCase).confirmSignupPhoneVerification(userCommand);

        assertThatThrownBy(() -> customerAuthService.confirmSignupPhoneVerification(command))
                .isInstanceOfSatisfying(CustomerException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(CustomerErrorCode.SIGNUP_VERIFICATION_UNAVAILABLE);
                    assertThat(exception).hasCause(unknownUserError);
                });
    }

    @Test
    @DisplayName("인증번호가 일치하면 user 인증 use case의 확인 결과를 반환한다")
    void confirmSignupPhoneVerificationWhenCodeMatchesReturnsResult() {
        CustomerPhoneVerificationConfirmCommand command = new CustomerPhoneVerificationConfirmCommand("01012345678", "123456");
        SignupPhoneVerificationConfirmCommand userCommand = new SignupPhoneVerificationConfirmCommand("01012345678", "123456");
        SignupPhoneVerificationConfirmResult confirmResult = new SignupPhoneVerificationConfirmResult(true, 600L);
        when(userSignupPhoneVerificationUseCase.confirmSignupPhoneVerification(userCommand)).thenReturn(confirmResult);

        CustomerPhoneVerificationConfirmResult result = customerAuthService.confirmSignupPhoneVerification(command);

        assertThat(result.phoneNumberVerified()).isTrue();
        assertThat(result.verifiedExpiresInSeconds()).isEqualTo(600L);
        verify(userSignupPhoneVerificationUseCase).confirmSignupPhoneVerification(userCommand);
    }

    @Test
    @DisplayName("가입 sid로 소셜 가입 기본 정보를 조회한다")
    void getSocialSignupInfoReturnsSocialSignupInfo() throws Exception {
        // given
        when(socialSignupRepository.findBySid(SIGNUP_SID))
                .thenReturn(Optional.of(objectMapper.writeValueAsString(socialSignupInfo())));

        // when
        SocialSignupInfoView result = customerAuthService.getSocialSignupInfo(SIGNUP_SID);

        // then
        assertThat(result.phoneNumber()).isEqualTo("01012345678");
        assertThat(result.name()).isEqualTo("소셜이름");
        assertThat(result.email()).isEqualTo("provider@example.com");
        assertThat(result.nickname()).isEqualTo("소셜닉네임");
        assertThat(result.profileImageUrl()).isEqualTo("https://image.example/profile.png");
        assertThat(result.gender()).isEqualTo("MALE");
        assertThat(result.birthDate()).isEqualTo(LocalDate.of(2000, 1, 1));
    }

    @Test
    @DisplayName("가입 sid가 없으면 소셜 가입 기본 정보 조회에 실패한다")
    void getSocialSignupInfoThrowsExceptionWhenSignupSidIsMissing() {
        assertThatThrownBy(() -> customerAuthService.getSocialSignupInfo(null))
                .isInstanceOfSatisfying(CustomerException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CustomerErrorCode.INVALID_SIGNUP_SESSION)
                );
    }

    @Test
    @DisplayName("임시 로그인 code가 있으면 원자적으로 소비한 토큰 정보를 반환한다")
    void exchangeTemporaryLoginCodeReturnsAtomicallyConsumedTokenInfo() throws Exception {
        // given
        UUID userId = UUID.randomUUID();
        SocialLoginTokenResult tokenResult =
                new SocialLoginTokenResult(userId, "access-token", "refresh-token");
        when(socialLoginCodeRepository.consumeByCode(TEMPORARY_LOGIN_CODE))
                .thenReturn(Optional.of(objectMapper.writeValueAsString(tokenResult)));

        // when
        SocialLoginTokenResult result =
                customerAuthService.exchangeTemporaryLoginCode(TEMPORARY_LOGIN_CODE);

        // then
        assertThat(result).isEqualTo(tokenResult);
        verify(socialLoginCodeRepository).consumeByCode(TEMPORARY_LOGIN_CODE);
    }

    @Test
    @DisplayName("임시 로그인 code가 없으면 교환에 실패한다")
    void exchangeTemporaryLoginCodeThrowsExceptionWhenCodeIsMissing() {
        assertThatThrownBy(() -> customerAuthService.exchangeTemporaryLoginCode(" "))
                .isInstanceOfSatisfying(CustomerException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CustomerErrorCode.INVALID_LOGIN_CODE)
                );
    }

    @Test
    @DisplayName("저장된 Refresh Token과 일치하면 Access Token을 재발급한다")
    void reissueAccessTokenReturnsNewAccessTokenWhenRefreshTokenMatches() {
        // given
        UUID userId = UUID.randomUUID();
        String refreshToken =
                jwtTokenProvider.createRefreshToken(jwtSubject(userId, UserRole.USER.name()));
        JwtTokenClaims refreshClaims = jwtTokenProvider.parseToken(refreshToken);
        when(userAccountUseCase.findById(userId)).thenReturn(Optional.of(customerView(userId)));
        when(refreshTokenStore.rotate(
                eq(refreshClaims.sessionId()),
                eq(refreshClaims.tokenId()),
                any(UUID.class),
                any(Duration.class)
        )).thenReturn(true);

        // when
        CustomerTokenReissueResult result = customerAuthService.reissueAccessToken(refreshToken);

        // then
        assertThat(result.userId()).isEqualTo(userId);
        JwtTokenClaims accessClaims = jwtTokenProvider.parseToken(result.accessToken());
        JwtTokenClaims rotatedRefreshClaims = jwtTokenProvider.parseToken(result.refreshToken());
        assertThat(accessClaims.tokenType()).isEqualTo(JwtTokenType.ACCESS);
        assertThat(accessClaims.sessionId()).isEqualTo(refreshClaims.sessionId());
        assertThat(accessClaims.tokenId()).isNotEqualTo(refreshClaims.tokenId());
        assertThat(accessClaims.nickname()).isEqualTo("customer");
        assertThat(accessClaims.email()).isEqualTo(EMAIL);
        assertThat(rotatedRefreshClaims.tokenType()).isEqualTo(JwtTokenType.REFRESH);
        assertThat(rotatedRefreshClaims.sessionId()).isEqualTo(refreshClaims.sessionId());
        assertThat(rotatedRefreshClaims.tokenId()).isNotEqualTo(refreshClaims.tokenId());
        assertThat(rotatedRefreshClaims.expiresAt()).isEqualTo(refreshClaims.expiresAt());
        assertThat(result.refreshTokenMaxAgeSeconds())
                .isEqualTo(Duration.between(NOW, rotatedRefreshClaims.expiresAt()).toSeconds());
        verify(refreshTokenStore).rotate(
                eq(refreshClaims.sessionId()),
                eq(refreshClaims.tokenId()),
                eq(rotatedRefreshClaims.tokenId()),
                any(Duration.class)
        );
    }

    @Test
    @DisplayName("탈퇴 유예 상태 구매자는 Refresh Token 재발급에 실패한다")
    void reissueAccessTokenThrowsExceptionWhenCustomerIsWithdrawing() {
        // given
        UUID userId = UUID.randomUUID();
        String refreshToken =
                jwtTokenProvider.createRefreshToken(jwtSubject(userId, UserRole.USER.name()));
        when(userAccountUseCase.findById(userId)).thenReturn(Optional.of(customerView(userId, UserStatus.WITHDRAWING)));

        // when, then
        assertThatThrownBy(() -> customerAuthService.reissueAccessToken(refreshToken))
                .isInstanceOfSatisfying(CustomerException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CustomerErrorCode.INVALID_REFRESH_TOKEN)
                );
        verify(refreshTokenStore, never())
                .rotate(any(UUID.class), any(UUID.class), any(UUID.class), any(Duration.class));
    }

    @Test
    @DisplayName("회전된 Refresh Token의 남은 TTL이 1ms 미만이면 Redis 저장 없이 재발급에 실패한다")
    void reissueAccessTokenThrowsExceptionWhenRotatedRefreshTokenTtlIsExpired() {
        // given
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        String previousRefreshToken = "previous-refresh-token";
        String rotatedRefreshToken = "rotated-refresh-token";
        JwtTokenProvider tokenProvider = mock(JwtTokenProvider.class);
        JwtTokenLifecycle tokenLifecycle = new JwtTokenLifecycle(
                tokenProvider,
                refreshTokenStore,
                sessionRevocationStore,
                accessTokenBlacklist,
                timeProvider()
        );
        CustomerJwtTokenAdapter jwtTokenAdapter = new CustomerJwtTokenAdapter(tokenLifecycle);
        CustomerAuthService service = new CustomerAuthService(
                socialSignupRepository,
                socialLoginCodeRepository,
                userAccountUseCase,
                jwtTokenAdapter,
                timeProvider(),
                objectMapper,
                Mappers.getMapper(CustomerViewMapper.class),
                signupContactVerificationAdapter
        );
        JwtTokenClaims previousRefreshClaims = new JwtTokenClaims(
                userId,
                sessionId,
                UUID.randomUUID(),
                UserRole.USER.name(),
                JwtTokenType.REFRESH,
                null,
                null,
                NOW
        );
        JwtTokenClaims rotatedRefreshClaims = new JwtTokenClaims(
                userId,
                sessionId,
                UUID.randomUUID(),
                UserRole.USER.name(),
                JwtTokenType.REFRESH,
                null,
                null,
                NOW
        );
        when(tokenProvider.parseToken(previousRefreshToken)).thenReturn(previousRefreshClaims);
        when(tokenProvider.createAccessToken(any(JwtSubject.class))).thenReturn("access-token");
        when(tokenProvider.createRefreshTokenForRotation(any(JwtSubject.class), eq(previousRefreshClaims.expiresAt())))
                .thenReturn(rotatedRefreshToken);
        when(tokenProvider.parseToken(rotatedRefreshToken)).thenReturn(rotatedRefreshClaims);
        when(userAccountUseCase.findById(userId)).thenReturn(Optional.of(customerView(userId)));

        // when, then
        assertThatThrownBy(() -> service.reissueAccessToken(previousRefreshToken))
                .isInstanceOfSatisfying(CustomerException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CustomerErrorCode.INVALID_REFRESH_TOKEN)
                );
        verify(refreshTokenStore, never())
                .rotate(any(UUID.class), any(UUID.class), any(UUID.class), any(Duration.class));
        verify(refreshTokenStore, never()).deleteBySessionId(any(UUID.class), any(UUID.class));
        verifyNoInteractions(sessionRevocationStore);
    }

    @Test
    @DisplayName("이전 Refresh Token 재사용이 감지되면 해당 세션을 삭제하고 재발급에 실패한다")
    void reissueAccessTokenDeletesSessionWhenRefreshTokenIsReused() {
        // given
        UUID userId = UUID.randomUUID();
        String refreshToken =
                jwtTokenProvider.createRefreshToken(jwtSubject(userId, UserRole.USER.name()));
        JwtTokenClaims refreshClaims = jwtTokenProvider.parseToken(refreshToken);
        when(userAccountUseCase.findById(userId)).thenReturn(Optional.of(customerView(userId)));
        when(refreshTokenStore.rotate(
                eq(refreshClaims.sessionId()),
                eq(refreshClaims.tokenId()),
                any(UUID.class),
                any(Duration.class)
        )).thenReturn(false);

        // when, then
        assertThatThrownBy(() -> customerAuthService.reissueAccessToken(refreshToken))
                .isInstanceOfSatisfying(CustomerException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CustomerErrorCode.INVALID_REFRESH_TOKEN)
        );
        verify(refreshTokenStore).deleteBySessionId(refreshClaims.userId(), refreshClaims.sessionId());
        verify(sessionRevocationStore).revoke(refreshClaims.sessionId());
    }

    @Test
    @DisplayName("판매자 Refresh Token이면 구매자 토큰 재발급에 실패한다")
    void reissueAccessTokenThrowsExceptionWhenTokenBelongsToSeller() {
        // given
        UUID userId = UUID.randomUUID();
        String refreshToken =
                jwtTokenProvider.createRefreshToken(jwtSubject(userId, UserRole.SELLER.name()));
        when(userAccountUseCase.findById(userId)).thenReturn(Optional.of(sellerView(userId)));

        // when, then
        assertThatThrownBy(() -> customerAuthService.reissueAccessToken(refreshToken))
                .isInstanceOfSatisfying(CustomerException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CustomerErrorCode.INVALID_REFRESH_TOKEN)
                );
    }

    @Test
    @DisplayName("로그아웃 시 Refresh Token을 삭제하고 로그인 세션을 폐기한다")
    void logoutDeletesRefreshTokenAndRevokesSession() {
        // given
        UUID userId = UUID.randomUUID();
        String accessToken =
                jwtTokenProvider.createAccessToken(jwtSubject(userId, UserRole.USER.name()));
        JwtTokenClaims accessClaims = jwtTokenProvider.parseToken(accessToken);

        // when
        customerAuthService.logout(new CustomerLogoutCommand(accessToken));

        // then
        verify(refreshTokenStore).deleteBySessionId(accessClaims.userId(), accessClaims.sessionId());
        verify(sessionRevocationStore).revoke(accessClaims.sessionId());
        verify(accessTokenBlacklist, never()).save(any(UUID.class), any(Duration.class));
    }

    @Test
    @DisplayName("회원 탈퇴 요청 시 상태를 탈퇴 유예로 바꾸고 사용자의 모든 세션을 폐기한다")
    void requestWithdrawalMarksCustomerWithdrawingAndRevokesAllSessions() {
        // given
        UUID userId = UUID.randomUUID();
        String accessToken =
                jwtTokenProvider.createAccessToken(jwtSubject(userId, UserRole.USER.name()));
        when(userAccountUseCase.findById(userId)).thenReturn(Optional.of(customerView(userId)));
        when(userAccountUseCase.requestWithdrawal(userId))
                .thenReturn(customerView(userId, UserStatus.WITHDRAWING));

        // when
        customerAuthService.requestWithdrawal(accessToken);

        // then
        verify(userAccountUseCase).requestWithdrawal(userId);
        verify(refreshTokenStore).deleteAllByUserId(userId);
        verify(sessionRevocationStore).revokeAll(userId);
    }

    @Test
    @DisplayName("내정보 조회 시 구매자가 아니면 실패한다")
    void getMyInfoThrowsExceptionWhenUserIsNotCustomer() {
        // given
        UUID userId = UUID.randomUUID();
        when(userAccountUseCase.findById(userId)).thenReturn(Optional.of(sellerView(userId)));

        // when, then
        assertThatThrownBy(() -> customerAuthService.getMyInfo(userId))
                .isInstanceOfSatisfying(CustomerException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CustomerErrorCode.USER_NOT_FOUND)
                );
    }

    @Test
    @DisplayName("30일이 지난 뒤 닉네임 수정 시 중복을 검증하고 닉네임만 저장한다")
    void updateNicknameUpdatesCustomerNickname() {
        // given
        UUID userId = UUID.randomUUID();
        String oldAccessToken = jwtTokenProvider.createAccessToken(jwtSubject(userId, UserRole.USER.name()));
        JwtTokenClaims oldAccessClaims = jwtTokenProvider.parseToken(oldAccessToken);
        CustomerNicknameUpdateCommand command = new CustomerNicknameUpdateCommand(
                "updated",
                oldAccessToken
        );
        when(userAccountUseCase.findById(userId)).thenReturn(Optional.of(customerView(userId)));
        when(userAccountUseCase.existsByNickname("updated")).thenReturn(false);
        when(userAccountUseCase.changeNickname(userId, "updated"))
                .thenReturn(customerView(userId, "updated", providerCreatedAt()));

        // when
        CustomerNicknameUpdateResult result = customerAuthService.updateNickname(command);
        JwtTokenClaims accessClaims = jwtTokenProvider.parseToken(result.accessToken());

        // then
        assertThat(result.customer().userId()).isEqualTo(userId);
        assertThat(result.customer().nickname()).isEqualTo("updated");
        assertThat(result.customer().name()).isEqualTo("구매자");
        assertThat(result.customer().birthDate()).isEqualTo(LocalDate.of(2000, 1, 1));
        assertThat(result.customer().phoneNumber()).isEqualTo("01012345678");
        assertThat(result.customer().email()).isEqualTo(EMAIL);
        assertThat(result.customer().role()).isEqualTo(UserRole.USER.name());
        assertThat(accessClaims.tokenType()).isEqualTo(JwtTokenType.ACCESS);
        assertThat(accessClaims.sessionId()).isEqualTo(oldAccessClaims.sessionId());
        assertThat(accessClaims.tokenId()).isNotEqualTo(oldAccessClaims.tokenId());
        assertThat(accessClaims.nickname()).isEqualTo("updated");
        assertThat(accessClaims.email()).isEqualTo(EMAIL);
        verify(userAccountUseCase).existsByNickname("updated");
        verify(userAccountUseCase).changeNickname(userId, "updated");
        verify(accessTokenBlacklist).save(eq(oldAccessClaims.tokenId()), any(Duration.class));
        verify(refreshTokenStore, never()).save(any(UUID.class), any(UUID.class), any(UUID.class), any(Duration.class));
        verifyNoInteractions(sessionRevocationStore);
    }

    @Test
    @DisplayName("가입 후 30일이 지나지 않았으면 닉네임 수정에 실패한다")
    void updateNicknameThrowsExceptionWhenChangedWithinThirtyDays() {
        // given
        UUID userId = UUID.randomUUID();
        String oldAccessToken = jwtTokenProvider.createAccessToken(jwtSubject(userId, UserRole.USER.name()));
        CustomerNicknameUpdateCommand command = new CustomerNicknameUpdateCommand(
                "updated",
                oldAccessToken
        );
        when(userAccountUseCase.findById(userId))
                .thenReturn(Optional.of(customerView(userId, NOW.minus(Duration.ofDays(1)))));
        when(userAccountUseCase.existsByNickname("updated")).thenReturn(false);

        // when, then
        assertThatThrownBy(() -> customerAuthService.updateNickname(command))
                .isInstanceOfSatisfying(CustomerException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CustomerErrorCode.NICKNAME_CHANGE_RESTRICTED)
                );
        verify(userAccountUseCase).existsByNickname("updated");
        verify(userAccountUseCase, never()).changeNickname(any(UUID.class), any(String.class));
    }

    @Test
    @DisplayName("같은 닉네임이면 닉네임 중복으로 실패한다")
    void updateNicknameThrowsExceptionWhenNicknameIsSame() {
        // given
        UUID userId = UUID.randomUUID();
        String oldAccessToken = jwtTokenProvider.createAccessToken(jwtSubject(userId, UserRole.USER.name()));
        CustomerNicknameUpdateCommand command = new CustomerNicknameUpdateCommand(
                "customer",
                oldAccessToken
        );
        when(userAccountUseCase.findById(userId)).thenReturn(Optional.of(customerView(userId, NOW)));
        when(userAccountUseCase.existsByNickname("customer")).thenReturn(true);

        // when, then
        assertThatThrownBy(() -> customerAuthService.updateNickname(command))
                .isInstanceOfSatisfying(CustomerException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CustomerErrorCode.DUPLICATED_NICKNAME)
                );
        verify(userAccountUseCase).existsByNickname("customer");
        verify(userAccountUseCase, never()).changeNickname(any(UUID.class), any(String.class));
    }

    @Test
    @DisplayName("이미 존재하는 닉네임이면 닉네임 수정에 실패한다")
    void updateNicknameThrowsExceptionWhenNicknameIsDuplicated() {
        // given
        UUID userId = UUID.randomUUID();
        String oldAccessToken = jwtTokenProvider.createAccessToken(jwtSubject(userId, UserRole.USER.name()));
        CustomerNicknameUpdateCommand command = new CustomerNicknameUpdateCommand(
                "updated",
                oldAccessToken
        );
        when(userAccountUseCase.findById(userId)).thenReturn(Optional.of(customerView(userId)));
        when(userAccountUseCase.existsByNickname("updated")).thenReturn(true);

        // when, then
        assertThatThrownBy(() -> customerAuthService.updateNickname(command))
                .isInstanceOfSatisfying(CustomerException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CustomerErrorCode.DUPLICATED_NICKNAME)
                );
    }

    @Test
    @DisplayName("저장 중 닉네임 unique 충돌이 발생하면 닉네임 중복으로 실패한다")
    void updateNicknameThrowsExceptionWhenNicknameSaveConflicts() {
        // given
        UUID userId = UUID.randomUUID();
        String oldAccessToken = jwtTokenProvider.createAccessToken(jwtSubject(userId, UserRole.USER.name()));
        CustomerNicknameUpdateCommand command = new CustomerNicknameUpdateCommand(
                "updated",
                oldAccessToken
        );
        when(userAccountUseCase.findById(userId)).thenReturn(Optional.of(customerView(userId)));
        when(userAccountUseCase.existsByNickname("updated")).thenReturn(false);
        when(userAccountUseCase.changeNickname(userId, "updated"))
                .thenThrow(new DataIntegrityViolationException("duplicated"));

        // when, then
        assertThatThrownBy(() -> customerAuthService.updateNickname(command))
                .isInstanceOfSatisfying(CustomerException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CustomerErrorCode.DUPLICATED_NICKNAME)
                );
    }

    @Test
    @DisplayName("이메일 중복 여부를 조회한다")
    void isEmailDuplicatedReturnsRepositoryResult() {
        when(userAccountUseCase.existsByEmail(EMAIL)).thenReturn(true);

        assertThat(customerAuthService.isEmailDuplicated(EMAIL)).isTrue();
    }

    @Test
    @DisplayName("전화번호 중복 여부를 조회한다")
    void isPhoneNumberDuplicatedReturnsRepositoryResult() {
        when(userAccountUseCase.existsByPhoneNumber("01012345678")).thenReturn(true);

        assertThat(customerAuthService.isPhoneNumberDuplicated("01012345678")).isTrue();
    }

    @Test
    @DisplayName("닉네임 중복 여부를 조회한다")
    void isNicknameDuplicatedReturnsRepositoryResult() {
        when(userAccountUseCase.existsByNickname("customer")).thenReturn(true);

        assertThat(customerAuthService.isNicknameDuplicated("customer")).isTrue();
    }

    private SocialSignupCommand signupCommand() {
        return new SocialSignupCommand(
                "01012345678",
                EMAIL,
                "customer",
                "구매자",
                LocalDate.of(2000, 1, 1),
                UserGender.FEMALE.name(),
                "https://image.example/request-profile.png",
                true,
                true
        );
    }

    private SocialSignupInfo socialSignupInfo() {
        return new SocialSignupInfo(
                ProviderType.NAVER,
                "naver-id",
                "provider@example.com",
                "소셜이름",
                "소셜닉네임",
                "01012345678",
                "https://image.example/profile.png",
                UserGender.MALE,
                LocalDate.of(2000, 1, 1)
        );
    }

    private TimeProvider timeProvider() {
        return new TimeProvider(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private JwtSubject jwtSubject(UUID userId, String role) {
        return new JwtSubject(userId, UUID.randomUUID(), role, "customer", EMAIL);
    }

    private UserView customerView(UUID userId) {
        return customerView(userId, UserStatus.ACTIVE);
    }

    private UserView customerView(UUID userId, UserStatus status) {
        return customerView(userId, "customer", providerCreatedAt(), status);
    }

    private UserView customerView(UUID userId, Instant nicknameChangedAt) {
        return customerView(userId, "customer", nicknameChangedAt, UserStatus.ACTIVE);
    }

    private UserView customerView(UUID userId, String nickname, Instant nicknameChangedAt) {
        return customerView(userId, nickname, nicknameChangedAt, UserStatus.ACTIVE);
    }

    private UserView customerView(UUID userId, String nickname, Instant nicknameChangedAt, UserStatus status) {
        return new UserView(
                userId,
                UserRole.USER,
                status,
                nickname,
                nicknameChangedAt,
                "구매자",
                LocalDate.of(2000, 1, 1),
                UserGender.MALE,
                "01012345678",
                null,
                EMAIL,
                UserGrade.BRONZE,
                0,
                true,
                ProviderType.NAVER,
                "naver-id",
                "provider@example.com"
        );
    }

    private UserView sellerView(UUID userId) {
        return new UserView(
                userId,
                UserRole.SELLER,
                UserStatus.ACTIVE,
                "seller",
                providerCreatedAt(),
                "판매자",
                LocalDate.of(1990, 1, 1),
                null,
                "01099998888",
                null,
                "seller@example.com",
                UserGrade.BRONZE,
                0,
                true,
                null,
                null,
                null
        );
    }

    private Instant providerCreatedAt() {
        return Instant.parse("2025-01-01T00:00:00Z");
    }

    private static class TestUserException extends BusinessException {

        TestUserException(TestUserErrorCode errorCode) {
            super(errorCode);
        }
    }

    private enum TestUserErrorCode implements ErrorCode {

        SIGNUP_PHONE_VERIFICATION_REQUIRED(400, "USER-101", "휴대폰 인증이 필요합니다."),
        SIGNUP_PHONE_VERIFICATION_CODE_MISMATCH(400, "USER-103", "인증번호가 올바르지 않습니다."),
        DUPLICATED_PHONE_NUMBER(409, "USER-107", "이미 사용 중인 전화번호입니다."),
        SIGNUP_EMAIL_VERIFICATION_REQUIRED(400, "USER-108", "이메일 인증이 필요합니다."),
        UNKNOWN_USER_ERROR(500, "USER-999", "알 수 없는 user 오류입니다.");

        private final int status;
        private final String code;
        private final String message;

        TestUserErrorCode(int status, String code, String message) {
            this.status = status;
            this.code = code;
            this.message = message;
        }

        @Override
        public int getStatus() {
            return status;
        }

        @Override
        public String getCode() {
            return code;
        }

        @Override
        public String getMessage() {
            return message;
        }
    }
}
