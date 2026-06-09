package com.sapari.seller.application.service;

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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.sapari.common.securityjwt.jwt.JwtProperties;
import com.sapari.common.securityjwt.jwt.JwtSubject;
import com.sapari.common.securityjwt.jwt.JwtTokenClaims;
import com.sapari.common.securityjwt.jwt.JwtTokenProvider;
import com.sapari.common.securityjwt.jwt.JwtTokenType;
import com.sapari.global.time.TimeProvider;
import com.sapari.seller.command.SellerLoginCommand;
import com.sapari.seller.command.SellerLogoutCommand;
import com.sapari.seller.command.SellerNicknameUpdateCommand;
import com.sapari.seller.command.SellerSignupCommand;
import com.sapari.seller.application.port.SellerBusinessRegistrationVerification;
import com.sapari.seller.application.port.SellerBusinessRegistrationVerifier;
import com.sapari.seller.domain.exception.SellerErrorCode;
import com.sapari.seller.domain.exception.SellerException;
import com.sapari.seller.domain.model.LocalCredential;
import com.sapari.seller.domain.model.SellerApprovalStatus;
import com.sapari.seller.domain.model.SellerBusinessType;
import com.sapari.seller.domain.model.SellerProfile;
import com.sapari.seller.domain.repository.LocalCredentialRepository;
import com.sapari.seller.domain.repository.SellerProfileRepository;
import com.sapari.seller.view.SellerLoginResult;
import com.sapari.seller.view.SellerNicknameUpdateResult;
import com.sapari.seller.view.SellerSignupResult;
import com.sapari.seller.view.SellerTokenReissueResult;
import com.sapari.common.securityjwt.store.AccessTokenBlacklist;
import com.sapari.common.securityjwt.store.RefreshTokenStore;
import com.sapari.common.securityjwt.store.SessionRevocationStore;
import com.sapari.user.model.ProviderType;
import com.sapari.user.model.UserGender;
import com.sapari.user.model.UserGrade;
import com.sapari.user.model.UserRole;
import com.sapari.user.model.UserStatus;
import com.sapari.user.port.UserAccountUseCase;
import com.sapari.user.view.UserView;

@DisplayName("판매자 인증 서비스 테스트")
class SellerAuthServiceTest {

    private static final String SECRET = "test-secret-key-for-jwt-provider-32bytes";
    private static final String EMAIL = "seller@example.com";
    private static final String PASSWORD = "Password1!";
    private static final String PASSWORD_HASH = "hashed-password";
    private static final Instant NOW = Instant.now();

    private final UserAccountUseCase userAccountUseCase = mock(UserAccountUseCase.class);
    private final LocalCredentialRepository localCredentialRepository =
            mock(LocalCredentialRepository.class);
    private final SellerProfileRepository sellerProfileRepository =
            mock(SellerProfileRepository.class);
    private final SellerBusinessRegistrationVerifier sellerBusinessRegistrationVerifier =
            mock(SellerBusinessRegistrationVerifier.class);
    private final SellerSignupProcessor sellerSignupProcessor =
            mock(SellerSignupProcessor.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final JwtTokenProvider jwtTokenProvider = new JwtTokenProvider(
            new JwtProperties("seller-test", SECRET, 3600L, 1209600L),
            timeProvider()
    );
    private final RefreshTokenStore refreshTokenStore =
            mock(RefreshTokenStore.class);
    private final SessionRevocationStore sessionRevocationStore =
            mock(SessionRevocationStore.class);
    private final AccessTokenBlacklist accessTokenBlacklist =
            mock(AccessTokenBlacklist.class);
    private final SellerAuthService sellerAuthService = new SellerAuthService(
            userAccountUseCase,
            localCredentialRepository,
            sellerProfileRepository,
            sellerBusinessRegistrationVerifier,
            sellerSignupProcessor,
            passwordEncoder,
            jwtTokenProvider,
            refreshTokenStore,
            sessionRevocationStore,
            accessTokenBlacklist,
            timeProvider()
    );

    @Test
    @DisplayName("회원가입 시 판매자 User와 비밀번호 해시를 저장한다")
    void signupSavesSellerUserAndLocalCredential() {
        // given
        UUID userId = UUID.randomUUID();
        SellerSignupCommand command = signupCommand(" 사파리 상점 ", "INDIVIDUAL");
        when(sellerBusinessRegistrationVerifier.verify(
                "1234567890",
                "판매자",
                LocalDate.of(2020, 1, 1)
        )).thenReturn(SellerBusinessRegistrationVerification.available());
        when(sellerSignupProcessor.signup(command, "사파리 상점", SellerBusinessType.INDIVIDUAL))
                .thenReturn(new SellerSignupResult(userId));

        // when
        SellerSignupResult result = sellerAuthService.signup(command);

        // then
        assertThat(result.userId()).isEqualTo(userId);
        verify(sellerProfileRepository).existsByStoreName("사파리 상점");
        verify(sellerSignupProcessor).signup(command, "사파리 상점", SellerBusinessType.INDIVIDUAL);
    }

    @Test
    @DisplayName("회원가입 시 법인 사업자 유형을 저장한다")
    void signupSavesCorporateBusinessType() {
        // given
        UUID userId = UUID.randomUUID();
        SellerSignupCommand command = signupCommand("CORPORATE");
        when(sellerBusinessRegistrationVerifier.verify(
                "1234567890",
                "판매자",
                LocalDate.of(2020, 1, 1)
        )).thenReturn(SellerBusinessRegistrationVerification.available());
        when(sellerSignupProcessor.signup(command, "사파리 상점", SellerBusinessType.CORPORATE))
                .thenReturn(new SellerSignupResult(userId));

        // when
        sellerAuthService.signup(command);

        // then
        verify(sellerSignupProcessor).signup(command, "사파리 상점", SellerBusinessType.CORPORATE);
    }

    @Test
    @DisplayName("사업자번호가 중복되면 회원가입에 실패한다")
    void signupThrowsExceptionWhenBusinessNumberIsDuplicated() {
        // given
        when(sellerBusinessRegistrationVerifier.verify(
                "1234567890",
                "판매자",
                LocalDate.of(2020, 1, 1)
        )).thenReturn(SellerBusinessRegistrationVerification.available());
        when(sellerProfileRepository.existsByBusinessNumber("1234567890")).thenReturn(true);

        // when, then
        assertThatThrownBy(() -> sellerAuthService.signup(signupCommand()))
                .isInstanceOfSatisfying(SellerException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(SellerErrorCode.DUPLICATED_BUSINESS_NUMBER)
                );
        verifyNoInteractions(sellerSignupProcessor);
    }

    @Test
    @DisplayName("상호명이 중복되면 회원가입에 실패한다")
    void signupThrowsExceptionWhenStoreNameIsDuplicated() {
        // given
        when(sellerBusinessRegistrationVerifier.verify(
                "1234567890",
                "판매자",
                LocalDate.of(2020, 1, 1)
        )).thenReturn(SellerBusinessRegistrationVerification.available());
        when(sellerProfileRepository.existsByStoreName("사파리 상점")).thenReturn(true);

        // when, then
        assertThatThrownBy(() -> sellerAuthService.signup(signupCommand()))
                .isInstanceOfSatisfying(SellerException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(SellerErrorCode.DUPLICATED_STORE_NAME)
                );
        verifyNoInteractions(sellerSignupProcessor);
    }

    @Test
    @DisplayName("사업자 유형이 올바르지 않으면 회원가입에 실패한다")
    void signupThrowsExceptionWhenBusinessTypeIsInvalid() {
        // when, then
        assertThatThrownBy(() -> sellerAuthService.signup(signupCommand("개인")))
                .isInstanceOfSatisfying(SellerException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(SellerErrorCode.INVALID_BUSINESS_TYPE)
                );
        verifyNoInteractions(sellerSignupProcessor);
    }

    @Test
    @DisplayName("가입 가능한 사업자등록번호가 아니면 회원가입에 실패한다")
    void signupThrowsExceptionWhenBusinessRegistrationIsInactive() {
        // given
        when(sellerBusinessRegistrationVerifier.verify(
                "1234567890",
                "판매자",
                LocalDate.of(2020, 1, 1)
        )).thenReturn(SellerBusinessRegistrationVerification.invalid());

        // when, then
        assertThatThrownBy(() -> sellerAuthService.signup(signupCommand()))
                .isInstanceOfSatisfying(SellerException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(SellerErrorCode.INVALID_BUSINESS_REGISTRATION)
                );
        verifyNoInteractions(sellerSignupProcessor);
    }

    @Test
    @DisplayName("사업자등록번호 상태조회가 불가능하면 회원가입에 실패한다")
    void signupThrowsExceptionWhenBusinessRegistrationCheckIsUnavailable() {
        // given
        when(sellerBusinessRegistrationVerifier.verify(
                "1234567890",
                "판매자",
                LocalDate.of(2020, 1, 1)
        )).thenReturn(SellerBusinessRegistrationVerification.unavailable());

        // when, then
        assertThatThrownBy(() -> sellerAuthService.signup(signupCommand()))
                .isInstanceOfSatisfying(SellerException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(SellerErrorCode.BUSINESS_REGISTRATION_CHECK_UNAVAILABLE)
                );
        verifyNoInteractions(sellerSignupProcessor);
    }

    @Test
    @DisplayName("회원가입 정보가 중복되면 SellerException이 발생한다")
    void signupThrowsExceptionWhenSignupInfoIsDuplicated() {
        // given
        when(sellerBusinessRegistrationVerifier.verify(
                "1234567890",
                "판매자",
                LocalDate.of(2020, 1, 1)
        )).thenReturn(SellerBusinessRegistrationVerification.available());
        when(sellerSignupProcessor.signup(
                any(SellerSignupCommand.class),
                eq("사파리 상점"),
                eq(SellerBusinessType.INDIVIDUAL)
        )).thenThrow(new DataIntegrityViolationException("duplicated"));

        // when, then
        assertThatThrownBy(() -> sellerAuthService.signup(signupCommand()))
                .isInstanceOfSatisfying(SellerException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(SellerErrorCode.DUPLICATED_SIGNUP_INFO)
                );
    }

    @Test
    @DisplayName("로그인 성공 시 Access Token과 Refresh Token을 발급하고 Refresh Token을 저장한다")
    void loginIssuesTokensAndStoresRefreshToken() {
        // given
        UUID userId = UUID.randomUUID();
        when(userAccountUseCase.findByEmailAndRole(EMAIL, UserRole.SELLER))
                .thenReturn(Optional.of(sellerView(userId)));
        when(localCredentialRepository.findById(userId))
                .thenReturn(Optional.of(LocalCredential.create(userId, PASSWORD_HASH, passwordChangedAt())));
        when(passwordEncoder.matches(PASSWORD, PASSWORD_HASH)).thenReturn(true);

        // when
        SellerLoginResult result = sellerAuthService.login(new SellerLoginCommand(EMAIL, PASSWORD));

        // then
        assertThat(result.userId()).isEqualTo(userId);
        JwtTokenClaims accessClaims = jwtTokenProvider.parseToken(result.accessToken());
        JwtTokenClaims refreshClaims = jwtTokenProvider.parseToken(result.refreshToken());
        assertThat(accessClaims.tokenType()).isEqualTo(JwtTokenType.ACCESS);
        assertThat(accessClaims.nickname()).isEqualTo("seller");
        assertThat(accessClaims.email()).isEqualTo(EMAIL);
        assertThat(refreshClaims.tokenType()).isEqualTo(JwtTokenType.REFRESH);
        assertThat(refreshClaims.sessionId()).isEqualTo(accessClaims.sessionId());
        assertThat(refreshClaims.tokenId()).isNotEqualTo(accessClaims.tokenId());
        assertThat(refreshClaims.nickname()).isNull();
        assertThat(refreshClaims.email()).isNull();
        verify(refreshTokenStore).save(
                eq(refreshClaims.sessionId()),
                eq(refreshClaims.tokenId()),
                any(Duration.class)
        );
    }

    @Test
    @DisplayName("비밀번호가 일치하지 않으면 로그인에 실패한다")
    void loginThrowsExceptionWhenPasswordDoesNotMatch() {
        // given
        UUID userId = UUID.randomUUID();
        when(userAccountUseCase.findByEmailAndRole(EMAIL, UserRole.SELLER))
                .thenReturn(Optional.of(sellerView(userId)));
        when(localCredentialRepository.findById(userId))
                .thenReturn(Optional.of(LocalCredential.create(userId, PASSWORD_HASH, passwordChangedAt())));
        when(passwordEncoder.matches(PASSWORD, PASSWORD_HASH)).thenReturn(false);

        // when, then
        assertThatThrownBy(() -> sellerAuthService.login(new SellerLoginCommand(EMAIL, PASSWORD)))
                .isInstanceOfSatisfying(SellerException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(SellerErrorCode.INVALID_LOGIN_CREDENTIALS)
                );
    }

    @Test
    @DisplayName("저장된 Refresh Token과 일치하면 Access Token을 재발급한다")
    void reissueAccessTokenReturnsNewAccessTokenWhenRefreshTokenMatches() {
        // given
        UUID userId = UUID.randomUUID();
        String refreshToken = jwtTokenProvider.createRefreshToken(jwtSubject(userId, UserRole.SELLER.name()));
        JwtTokenClaims refreshClaims = jwtTokenProvider.parseToken(refreshToken);
        when(userAccountUseCase.findById(userId)).thenReturn(Optional.of(sellerView(userId)));
        when(refreshTokenStore.rotate(
                eq(refreshClaims.sessionId()),
                eq(refreshClaims.tokenId()),
                any(UUID.class),
                any(Duration.class)
        )).thenReturn(true);

        // when
        SellerTokenReissueResult result = sellerAuthService.reissueAccessToken(refreshToken);

        // then
        assertThat(result.userId()).isEqualTo(userId);
        JwtTokenClaims accessClaims = jwtTokenProvider.parseToken(result.accessToken());
        JwtTokenClaims rotatedRefreshClaims = jwtTokenProvider.parseToken(result.refreshToken());
        assertThat(accessClaims.tokenType()).isEqualTo(JwtTokenType.ACCESS);
        assertThat(accessClaims.sessionId()).isEqualTo(refreshClaims.sessionId());
        assertThat(accessClaims.tokenId()).isNotEqualTo(refreshClaims.tokenId());
        assertThat(accessClaims.nickname()).isEqualTo("seller");
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
    @DisplayName("회전된 Refresh Token의 남은 TTL이 1ms 미만이면 Redis 저장 없이 재발급에 실패한다")
    void reissueAccessTokenThrowsExceptionWhenRotatedRefreshTokenTtlIsExpired() {
        // given
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        String previousRefreshToken = "previous-refresh-token";
        String rotatedRefreshToken = "rotated-refresh-token";
        JwtTokenProvider tokenProvider = mock(JwtTokenProvider.class);
        SellerAuthService service = new SellerAuthService(
                userAccountUseCase,
                localCredentialRepository,
                sellerProfileRepository,
                sellerBusinessRegistrationVerifier,
                sellerSignupProcessor,
                passwordEncoder,
                tokenProvider,
                refreshTokenStore,
                sessionRevocationStore,
                accessTokenBlacklist,
                timeProvider()
        );
        JwtTokenClaims previousRefreshClaims = new JwtTokenClaims(
                userId,
                sessionId,
                UUID.randomUUID(),
                UserRole.SELLER.name(),
                JwtTokenType.REFRESH,
                null,
                null,
                NOW
        );
        JwtTokenClaims rotatedRefreshClaims = new JwtTokenClaims(
                userId,
                sessionId,
                UUID.randomUUID(),
                UserRole.SELLER.name(),
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
        when(userAccountUseCase.findById(userId)).thenReturn(Optional.of(sellerView(userId)));

        // when, then
        assertThatThrownBy(() -> service.reissueAccessToken(previousRefreshToken))
                .isInstanceOfSatisfying(SellerException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(SellerErrorCode.INVALID_REFRESH_TOKEN)
                );
        verify(refreshTokenStore, never())
                .rotate(any(UUID.class), any(UUID.class), any(UUID.class), any(Duration.class));
        verify(refreshTokenStore, never()).deleteBySessionId(any(UUID.class));
        verifyNoInteractions(sessionRevocationStore);
    }

    @Test
    @DisplayName("Refresh Token이 비어 있으면 재발급에 실패한다")
    void reissueAccessTokenThrowsExceptionWhenRefreshTokenIsBlank() {
        assertThatThrownBy(() -> sellerAuthService.reissueAccessToken(" "))
                .isInstanceOfSatisfying(SellerException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(SellerErrorCode.INVALID_REFRESH_TOKEN)
                );
    }

    @Test
    @DisplayName("이전 Refresh Token 재사용이 감지되면 해당 세션을 삭제하고 재발급에 실패한다")
    void reissueAccessTokenThrowsExceptionWhenSavedRefreshTokenDoesNotMatch() {
        // given
        UUID userId = UUID.randomUUID();
        String refreshToken = jwtTokenProvider.createRefreshToken(jwtSubject(userId, UserRole.SELLER.name()));
        JwtTokenClaims refreshClaims = jwtTokenProvider.parseToken(refreshToken);
        when(userAccountUseCase.findById(userId)).thenReturn(Optional.of(sellerView(userId)));
        when(refreshTokenStore.rotate(
                eq(refreshClaims.sessionId()),
                eq(refreshClaims.tokenId()),
                any(UUID.class),
                any(Duration.class)
        )).thenReturn(false);

        // when, then
        assertThatThrownBy(() -> sellerAuthService.reissueAccessToken(refreshToken))
                .isInstanceOfSatisfying(SellerException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(SellerErrorCode.INVALID_REFRESH_TOKEN)
        );
        verify(refreshTokenStore).deleteBySessionId(refreshClaims.sessionId());
        verify(sessionRevocationStore).revoke(refreshClaims.sessionId());
    }

    @Test
    @DisplayName("판매자가 아닌 Refresh Token이면 재발급에 실패한다")
    void reissueAccessTokenThrowsExceptionWhenUserIsNotSeller() {
        // given
        UUID userId = UUID.randomUUID();
        String refreshToken = jwtTokenProvider.createRefreshToken(jwtSubject(userId, UserRole.USER.name()));
        when(userAccountUseCase.findById(userId)).thenReturn(Optional.of(memberView(userId)));

        // when, then
        assertThatThrownBy(() -> sellerAuthService.reissueAccessToken(refreshToken))
                .isInstanceOfSatisfying(SellerException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(SellerErrorCode.INVALID_REFRESH_TOKEN)
                );
    }

    @Test
    @DisplayName("로그아웃 시 Refresh Token을 삭제하고 로그인 세션을 폐기한다")
    void logoutDeletesRefreshTokenAndRevokesSession() {
        // given
        UUID userId = UUID.randomUUID();
        String accessToken = jwtTokenProvider.createAccessToken(jwtSubject(userId, UserRole.SELLER.name()));
        JwtTokenClaims accessClaims = jwtTokenProvider.parseToken(accessToken);

        // when
        sellerAuthService.logout(new SellerLogoutCommand(userId, accessToken));

        // then
        verify(refreshTokenStore).deleteBySessionId(accessClaims.sessionId());
        verify(sessionRevocationStore).revoke(accessClaims.sessionId());
        verify(accessTokenBlacklist, never()).save(any(UUID.class), any(Duration.class));
    }

    @Test
    @DisplayName("판매자가 아닌 사용자의 내정보 조회는 실패한다")
    void getMyInfoThrowsExceptionWhenUserIsNotSeller() {
        // given
        UUID userId = UUID.randomUUID();
        when(userAccountUseCase.findById(userId)).thenReturn(Optional.of(memberView(userId)));

        // when, then
        assertThatThrownBy(() -> sellerAuthService.getMyInfo(userId))
                .isInstanceOfSatisfying(SellerException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(SellerErrorCode.USER_NOT_FOUND)
                );
    }

    @Test
    @DisplayName("내정보 조회 시 판매자 프로필 정보를 함께 반환한다")
    void getMyInfoReturnsSellerProfile() {
        // given
        UUID userId = UUID.randomUUID();
        when(userAccountUseCase.findById(userId)).thenReturn(Optional.of(sellerView(userId)));
        when(sellerProfileRepository.findByUserId(userId)).thenReturn(Optional.of(sellerProfile(userId)));

        // when
        var result = sellerAuthService.getMyInfo(userId);

        // then
        assertThat(result.userId()).isEqualTo(userId);
        assertThat(result.nickname()).isEqualTo("seller");
        assertThat(result.status()).isEqualTo(UserStatus.ACTIVE.name());
        assertThat(result.storeName()).isEqualTo("사파리 상점");
        assertThat(result.businessNumber()).isEqualTo("1234567890");
        assertThat(result.businessType()).isEqualTo(SellerBusinessType.INDIVIDUAL.name());
        assertThat(result.approvalStatus()).isEqualTo(SellerApprovalStatus.PENDING.name());
        assertThat(result.rejectionReason()).isNull();
        assertThat(result.approvedAt()).isNull();
    }

    @Test
    @DisplayName("내정보 조회 시 판매자 프로필이 없으면 실패한다")
    void getMyInfoThrowsExceptionWhenSellerProfileDoesNotExist() {
        // given
        UUID userId = UUID.randomUUID();
        when(userAccountUseCase.findById(userId)).thenReturn(Optional.of(sellerView(userId)));
        when(sellerProfileRepository.findByUserId(userId)).thenReturn(Optional.empty());

        // when, then
        assertThatThrownBy(() -> sellerAuthService.getMyInfo(userId))
                .isInstanceOfSatisfying(SellerException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(SellerErrorCode.SELLER_PROFILE_NOT_FOUND)
                );
    }

    @Test
    @DisplayName("30일이 지난 뒤 닉네임 수정 시 판매자 닉네임만 갱신한다")
    void updateNicknameUpdatesSellerNickname() {
        // given
        UUID userId = UUID.randomUUID();
        String oldAccessToken = jwtTokenProvider.createAccessToken(jwtSubject(userId, UserRole.SELLER.name()));
        JwtTokenClaims oldAccessClaims = jwtTokenProvider.parseToken(oldAccessToken);
        SellerNicknameUpdateCommand command = new SellerNicknameUpdateCommand(
                userId,
                "updated",
                oldAccessToken
        );
        when(userAccountUseCase.findById(userId)).thenReturn(Optional.of(sellerView(userId)));
        when(sellerProfileRepository.findByUserId(userId)).thenReturn(Optional.of(sellerProfile(userId)));
        when(userAccountUseCase.existsByNickname("updated")).thenReturn(false);
        when(userAccountUseCase.changeNickname(userId, "updated"))
                .thenReturn(sellerView(userId, "updated", passwordChangedAt()));

        // when
        SellerNicknameUpdateResult result = sellerAuthService.updateNickname(command);
        JwtTokenClaims accessClaims = jwtTokenProvider.parseToken(result.accessToken());

        // then
        assertThat(result.seller().userId()).isEqualTo(userId);
        assertThat(result.seller().nickname()).isEqualTo("updated");
        assertThat(result.seller().name()).isEqualTo("판매자");
        assertThat(result.seller().birthDate()).isEqualTo(LocalDate.of(1990, 1, 1));
        assertThat(result.seller().phoneNumber()).isEqualTo("01012345678");
        assertThat(result.seller().email()).isEqualTo(EMAIL);
        assertThat(result.seller().role()).isEqualTo(UserRole.SELLER.name());
        assertThat(result.seller().storeName()).isEqualTo("사파리 상점");
        assertThat(result.seller().businessNumber()).isEqualTo("1234567890");
        assertThat(result.seller().businessType()).isEqualTo(SellerBusinessType.INDIVIDUAL.name());
        assertThat(result.seller().approvalStatus()).isEqualTo(SellerApprovalStatus.PENDING.name());
        assertThat(accessClaims.tokenType()).isEqualTo(JwtTokenType.ACCESS);
        assertThat(accessClaims.sessionId()).isEqualTo(oldAccessClaims.sessionId());
        assertThat(accessClaims.tokenId()).isNotEqualTo(oldAccessClaims.tokenId());
        assertThat(accessClaims.nickname()).isEqualTo("updated");
        assertThat(accessClaims.email()).isEqualTo(EMAIL);
        verify(userAccountUseCase).existsByNickname("updated");
        verify(userAccountUseCase).changeNickname(userId, "updated");
        verify(accessTokenBlacklist).save(eq(oldAccessClaims.tokenId()), any(Duration.class));
        verify(refreshTokenStore, never()).save(any(UUID.class), any(UUID.class), any(Duration.class));
        verifyNoInteractions(sessionRevocationStore);
    }

    @Test
    @DisplayName("가입 후 30일이 지나지 않았으면 닉네임 수정에 실패한다")
    void updateNicknameThrowsExceptionWhenChangedWithinThirtyDays() {
        // given
        UUID userId = UUID.randomUUID();
        String oldAccessToken = jwtTokenProvider.createAccessToken(jwtSubject(userId, UserRole.SELLER.name()));
        SellerNicknameUpdateCommand command = new SellerNicknameUpdateCommand(
                userId,
                "updated",
                oldAccessToken
        );
        when(userAccountUseCase.findById(userId))
                .thenReturn(Optional.of(sellerView(userId, NOW.minus(Duration.ofDays(1)))));
        when(userAccountUseCase.existsByNickname("updated")).thenReturn(false);

        // when, then
        assertThatThrownBy(() -> sellerAuthService.updateNickname(command))
                .isInstanceOfSatisfying(SellerException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(SellerErrorCode.NICKNAME_CHANGE_RESTRICTED)
                );
        verify(userAccountUseCase).existsByNickname("updated");
        verify(userAccountUseCase, never()).changeNickname(any(UUID.class), any(String.class));
    }

    @Test
    @DisplayName("같은 닉네임이면 닉네임 중복으로 실패한다")
    void updateNicknameThrowsExceptionWhenNicknameIsSame() {
        // given
        UUID userId = UUID.randomUUID();
        String oldAccessToken = jwtTokenProvider.createAccessToken(jwtSubject(userId, UserRole.SELLER.name()));
        SellerNicknameUpdateCommand command = new SellerNicknameUpdateCommand(
                userId,
                "seller",
                oldAccessToken
        );
        when(userAccountUseCase.findById(userId)).thenReturn(Optional.of(sellerView(userId, NOW)));
        when(userAccountUseCase.existsByNickname("seller")).thenReturn(true);

        // when, then
        assertThatThrownBy(() -> sellerAuthService.updateNickname(command))
                .isInstanceOfSatisfying(SellerException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(SellerErrorCode.DUPLICATED_NICKNAME)
                );
        verify(userAccountUseCase).existsByNickname("seller");
        verify(userAccountUseCase, never()).changeNickname(any(UUID.class), any(String.class));
    }

    @Test
    @DisplayName("이미 존재하는 닉네임이면 닉네임 수정에 실패한다")
    void updateNicknameThrowsExceptionWhenNicknameIsDuplicated() {
        // given
        UUID userId = UUID.randomUUID();
        String oldAccessToken = jwtTokenProvider.createAccessToken(jwtSubject(userId, UserRole.SELLER.name()));
        SellerNicknameUpdateCommand command = new SellerNicknameUpdateCommand(
                userId,
                "updated",
                oldAccessToken
        );
        when(userAccountUseCase.findById(userId)).thenReturn(Optional.of(sellerView(userId)));
        when(userAccountUseCase.existsByNickname("updated")).thenReturn(true);

        // when, then
        assertThatThrownBy(() -> sellerAuthService.updateNickname(command))
                .isInstanceOfSatisfying(SellerException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(SellerErrorCode.DUPLICATED_NICKNAME)
                );
    }

    @Test
    @DisplayName("저장 중 닉네임 unique 충돌이 발생하면 닉네임 중복으로 실패한다")
    void updateNicknameThrowsExceptionWhenNicknameSaveConflicts() {
        // given
        UUID userId = UUID.randomUUID();
        String oldAccessToken = jwtTokenProvider.createAccessToken(jwtSubject(userId, UserRole.SELLER.name()));
        SellerNicknameUpdateCommand command = new SellerNicknameUpdateCommand(
                userId,
                "updated",
                oldAccessToken
        );
        when(userAccountUseCase.findById(userId)).thenReturn(Optional.of(sellerView(userId)));
        when(userAccountUseCase.existsByNickname("updated")).thenReturn(false);
        when(userAccountUseCase.changeNickname(userId, "updated"))
                .thenThrow(new DataIntegrityViolationException("duplicated"));

        // when, then
        assertThatThrownBy(() -> sellerAuthService.updateNickname(command))
                .isInstanceOfSatisfying(SellerException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(SellerErrorCode.DUPLICATED_NICKNAME)
                );
    }

    @Test
    @DisplayName("이메일 중복 여부를 조회한다")
    void isEmailDuplicatedReturnsRepositoryResult() {
        // given
        when(userAccountUseCase.existsByEmail(EMAIL)).thenReturn(true);

        // when, then
        assertThat(sellerAuthService.isEmailDuplicated(EMAIL)).isTrue();
    }

    @Test
    @DisplayName("전화번호 중복 여부를 조회한다")
    void isPhoneNumberDuplicatedReturnsRepositoryResult() {
        // given
        when(userAccountUseCase.existsByPhoneNumber("01012345678")).thenReturn(true);

        // when, then
        assertThat(sellerAuthService.isPhoneNumberDuplicated("01012345678")).isTrue();
    }

    @Test
    @DisplayName("닉네임 중복 여부를 조회한다")
    void isNicknameDuplicatedReturnsRepositoryResult() {
        // given
        when(userAccountUseCase.existsByNickname("seller")).thenReturn(true);

        // when, then
        assertThat(sellerAuthService.isNicknameDuplicated("seller")).isTrue();
    }

    @Test
    @DisplayName("상호명 중복 여부를 조회한다")
    void isStoreNameDuplicatedReturnsRepositoryResult() {
        // given
        when(sellerProfileRepository.existsByStoreName("사파리 상점")).thenReturn(true);

        // when, then
        assertThat(sellerAuthService.isStoreNameDuplicated("사파리 상점")).isTrue();
    }

    private SellerSignupCommand signupCommand() {
        return signupCommand("INDIVIDUAL");
    }

    private SellerSignupCommand signupCommand(String businessType) {
        return signupCommand("사파리 상점", businessType);
    }

    private SellerSignupCommand signupCommand(String storeName, String businessType) {
        return new SellerSignupCommand(
                EMAIL,
                PASSWORD,
                "seller",
                "판매자",
                "01012345678",
                LocalDate.of(1990, 1, 1),
                true,
                storeName,
                "1234567890",
                LocalDate.of(2020, 1, 1),
                businessType
        );
    }

    private TimeProvider timeProvider() {
        return new TimeProvider(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private JwtSubject jwtSubject(UUID userId, String role) {
        return new JwtSubject(userId, UUID.randomUUID(), role, "seller", EMAIL);
    }

    private UserView sellerView(UUID userId) {
        return sellerView(userId, "seller", passwordChangedAt());
    }

    private UserView sellerView(UUID userId, Instant nicknameChangedAt) {
        return sellerView(userId, "seller", nicknameChangedAt);
    }

    private UserView sellerView(UUID userId, String nickname, Instant nicknameChangedAt) {
        return new UserView(
                userId,
                UserRole.SELLER,
                UserStatus.ACTIVE,
                nickname,
                nicknameChangedAt,
                "판매자",
                LocalDate.of(1990, 1, 1),
                null,
                "01012345678",
                null,
                EMAIL,
                UserGrade.BRONZE,
                0,
                true,
                null,
                null,
                null
        );
    }

    private UserView memberView(UUID userId) {
        return new UserView(
                userId,
                UserRole.USER,
                UserStatus.ACTIVE,
                "member",
                passwordChangedAt(),
                "회원",
                LocalDate.of(1990, 1, 1),
                UserGender.MALE,
                "01012345678",
                null,
                "member@example.com",
                UserGrade.BRONZE,
                0,
                false,
                ProviderType.KAKAO,
                "provider-id",
                "provider@example.com"
        );
    }

    private Instant passwordChangedAt() {
        return Instant.parse("2025-01-01T00:00:00Z");
    }

    private SellerProfile sellerProfile(UUID userId) {
        return SellerProfile.createPending(
                userId,
                "사파리 상점",
                "1234567890",
                SellerBusinessType.INDIVIDUAL
        );
    }
}
