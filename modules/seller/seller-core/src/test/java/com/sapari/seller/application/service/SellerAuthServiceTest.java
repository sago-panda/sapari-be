package com.sapari.seller.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.sapari.common.web.security.jwt.JwtProperties;
import com.sapari.common.web.security.jwt.JwtSubject;
import com.sapari.common.web.security.jwt.JwtTokenProvider;
import com.sapari.common.web.security.jwt.JwtTokenType;
import com.sapari.seller.command.SellerLoginCommand;
import com.sapari.seller.command.SellerLogoutCommand;
import com.sapari.seller.command.SellerMeUpdateCommand;
import com.sapari.seller.command.SellerSignupCommand;
import com.sapari.seller.domain.exception.SellerErrorCode;
import com.sapari.seller.domain.exception.SellerException;
import com.sapari.seller.domain.model.LocalCredential;
import com.sapari.seller.domain.repository.LocalCredentialRepository;
import com.sapari.seller.result.SellerLoginResult;
import com.sapari.seller.result.SellerMeResult;
import com.sapari.seller.result.SellerSignupResult;
import com.sapari.seller.result.SellerTokenReissueResult;
import com.sapari.user.domain.model.ProviderType;
import com.sapari.user.domain.model.User;
import com.sapari.user.domain.model.UserRole;
import com.sapari.user.domain.repository.UserRepository;
import com.sapari.user.infrastructure.security.redis.AccessTokenBlacklistRedisRepository;
import com.sapari.user.infrastructure.security.redis.RefreshTokenRedisRepository;

@DisplayName("판매자 인증 서비스 테스트")
class SellerAuthServiceTest {

    private static final String SECRET = "test-secret-key-for-jwt-provider-32bytes";
    private static final String EMAIL = "seller@example.com";
    private static final String PASSWORD = "Password1!";
    private static final String PASSWORD_HASH = "hashed-password";

    private final UserRepository userRepository = mock(UserRepository.class);
    private final LocalCredentialRepository localCredentialRepository =
            mock(LocalCredentialRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final JwtTokenProvider jwtTokenProvider = new JwtTokenProvider(
            new JwtProperties("seller-test", SECRET, 3600L, 1209600L)
    );
    private final RefreshTokenRedisRepository refreshTokenRedisRepository =
            mock(RefreshTokenRedisRepository.class);
    private final AccessTokenBlacklistRedisRepository accessTokenBlacklistRedisRepository =
            mock(AccessTokenBlacklistRedisRepository.class);
    private final SellerAuthService sellerAuthService = new SellerAuthService(
            userRepository,
            localCredentialRepository,
            passwordEncoder,
            jwtTokenProvider,
            refreshTokenRedisRepository,
            accessTokenBlacklistRedisRepository
    );

    @Test
    @DisplayName("회원가입 시 판매자 User와 비밀번호 해시를 저장한다")
    void signupSavesSellerUserAndLocalCredential() {
        // given
        UUID userId = UUID.randomUUID();
        SellerSignupCommand command = signupCommand();
        when(passwordEncoder.encode(PASSWORD)).thenReturn(PASSWORD_HASH);
        when(userRepository.save(any(User.class))).thenAnswer(invocation ->
                ((User) invocation.getArgument(0)).toBuilder()
                        .userId(userId)
                        .build()
        );
        when(localCredentialRepository.save(any(LocalCredential.class))).thenAnswer(invocation ->
                invocation.getArgument(0)
        );

        // when
        SellerSignupResult result = sellerAuthService.signup(command);

        // then
        assertThat(result.userId()).isEqualTo(userId);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().role()).isEqualTo(UserRole.SELLER);
        assertThat(userCaptor.getValue().email()).isEqualTo(EMAIL);

        ArgumentCaptor<LocalCredential> credentialCaptor = ArgumentCaptor.forClass(LocalCredential.class);
        verify(localCredentialRepository).save(credentialCaptor.capture());
        assertThat(credentialCaptor.getValue().userId()).isEqualTo(userId);
        assertThat(credentialCaptor.getValue().passwordHash()).isEqualTo(PASSWORD_HASH);
    }

    @Test
    @DisplayName("회원가입 정보가 중복되면 SellerException이 발생한다")
    void signupThrowsExceptionWhenSignupInfoIsDuplicated() {
        // given
        when(userRepository.save(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("duplicated"));

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
        User seller = createSeller(userId);
        when(userRepository.findByEmailAndRole(EMAIL, UserRole.SELLER)).thenReturn(Optional.of(seller));
        when(localCredentialRepository.findById(userId))
                .thenReturn(Optional.of(LocalCredential.create(userId, PASSWORD_HASH)));
        when(passwordEncoder.matches(PASSWORD, PASSWORD_HASH)).thenReturn(true);

        // when
        SellerLoginResult result = sellerAuthService.login(new SellerLoginCommand(EMAIL, PASSWORD));

        // then
        assertThat(result.userId()).isEqualTo(userId);
        assertThat(jwtTokenProvider.getTokenType(result.accessToken())).isEqualTo(JwtTokenType.ACCESS);
        assertThat(jwtTokenProvider.getTokenType(result.refreshToken())).isEqualTo(JwtTokenType.REFRESH);
        verify(refreshTokenRedisRepository).save(userId, result.refreshToken());
    }

    @Test
    @DisplayName("비밀번호가 일치하지 않으면 로그인에 실패한다")
    void loginThrowsExceptionWhenPasswordDoesNotMatch() {
        // given
        UUID userId = UUID.randomUUID();
        when(userRepository.findByEmailAndRole(EMAIL, UserRole.SELLER)).thenReturn(Optional.of(createSeller(userId)));
        when(localCredentialRepository.findById(userId))
                .thenReturn(Optional.of(LocalCredential.create(userId, PASSWORD_HASH)));
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
        String refreshToken = jwtTokenProvider.createRefreshToken(new JwtSubject(userId, UserRole.SELLER.name()));
        when(userRepository.findById(userId)).thenReturn(Optional.of(createSeller(userId)));
        when(refreshTokenRedisRepository.findByUserId(userId)).thenReturn(Optional.of(refreshToken));

        // when
        SellerTokenReissueResult result = sellerAuthService.reissueAccessToken(refreshToken);

        // then
        assertThat(result.userId()).isEqualTo(userId);
        assertThat(jwtTokenProvider.getTokenType(result.accessToken())).isEqualTo(JwtTokenType.ACCESS);
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
    @DisplayName("저장된 Refresh Token과 다르면 재발급에 실패한다")
    void reissueAccessTokenThrowsExceptionWhenSavedRefreshTokenDoesNotMatch() {
        // given
        UUID userId = UUID.randomUUID();
        String refreshToken = jwtTokenProvider.createRefreshToken(new JwtSubject(userId, UserRole.SELLER.name()));
        when(userRepository.findById(userId)).thenReturn(Optional.of(createSeller(userId)));
        when(refreshTokenRedisRepository.findByUserId(userId)).thenReturn(Optional.of("different-token"));

        // when, then
        assertThatThrownBy(() -> sellerAuthService.reissueAccessToken(refreshToken))
                .isInstanceOfSatisfying(SellerException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(SellerErrorCode.INVALID_REFRESH_TOKEN)
                );
    }

    @Test
    @DisplayName("판매자가 아닌 Refresh Token이면 재발급에 실패한다")
    void reissueAccessTokenThrowsExceptionWhenUserIsNotSeller() {
        // given
        UUID userId = UUID.randomUUID();
        String refreshToken = jwtTokenProvider.createRefreshToken(new JwtSubject(userId, UserRole.USER.name()));
        when(userRepository.findById(userId)).thenReturn(Optional.of(createMember(userId)));

        // when, then
        assertThatThrownBy(() -> sellerAuthService.reissueAccessToken(refreshToken))
                .isInstanceOfSatisfying(SellerException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(SellerErrorCode.INVALID_REFRESH_TOKEN)
                );
    }

    @Test
    @DisplayName("로그아웃 시 Refresh Token을 삭제하고 Access Token을 blacklist에 저장한다")
    void logoutDeletesRefreshTokenAndBlacklistsAccessToken() {
        // given
        UUID userId = UUID.randomUUID();
        String accessToken = jwtTokenProvider.createAccessToken(new JwtSubject(userId, UserRole.SELLER.name()));

        // when
        sellerAuthService.logout(new SellerLogoutCommand(userId, accessToken));

        // then
        verify(refreshTokenRedisRepository).delete(userId);

        ArgumentCaptor<Duration> durationCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(accessTokenBlacklistRedisRepository).save(eq(accessToken), durationCaptor.capture());
        assertThat(durationCaptor.getValue()).isPositive();
    }

    @Test
    @DisplayName("판매자가 아닌 사용자의 내정보 조회는 실패한다")
    void getMyInfoThrowsExceptionWhenUserIsNotSeller() {
        // given
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(createMember(userId)));

        // when, then
        assertThatThrownBy(() -> sellerAuthService.getMyInfo(userId))
                .isInstanceOfSatisfying(SellerException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(SellerErrorCode.USER_NOT_FOUND)
                );
    }

    @Test
    @DisplayName("내정보 수정 시 판매자 프로필을 갱신한다")
    void updateMyInfoUpdatesSellerProfile() {
        // given
        UUID userId = UUID.randomUUID();
        SellerMeUpdateCommand command = new SellerMeUpdateCommand(
                userId,
                "updated",
                "판매자",
                LocalDate.of(1993, 3, 3),
                "01099998888",
                "profile/key",
                "updated@example.com",
                true
        );
        when(userRepository.findById(userId)).thenReturn(Optional.of(createSeller(userId)));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        SellerMeResult result = sellerAuthService.updateMyInfo(command);

        // then
        assertThat(result.userId()).isEqualTo(userId);
        assertThat(result.nickname()).isEqualTo("updated");
        assertThat(result.email()).isEqualTo("updated@example.com");
        assertThat(result.role()).isEqualTo(UserRole.SELLER.name());
    }

    @Test
    @DisplayName("이메일 중복 여부를 조회한다")
    void isEmailDuplicatedReturnsRepositoryResult() {
        // given
        when(userRepository.existsByEmail(EMAIL)).thenReturn(true);

        // when, then
        assertThat(sellerAuthService.isEmailDuplicated(EMAIL)).isTrue();
    }

    @Test
    @DisplayName("전화번호 중복 여부를 조회한다")
    void isPhoneNumberDuplicatedReturnsRepositoryResult() {
        // given
        when(userRepository.existsByPhoneNumber("01012345678")).thenReturn(true);

        // when, then
        assertThat(sellerAuthService.isPhoneNumberDuplicated("01012345678")).isTrue();
    }

    private SellerSignupCommand signupCommand() {
        return new SellerSignupCommand(
                EMAIL,
                PASSWORD,
                "seller",
                "판매자",
                "01012345678",
                LocalDate.of(1990, 1, 1),
                true
        );
    }

    private User createSeller(UUID userId) {
        return User.createSeller(
                "seller",
                "판매자",
                LocalDate.of(1990, 1, 1),
                "01012345678",
                EMAIL,
                true
        ).toBuilder()
                .userId(userId)
                .build();
    }

    private User createMember(UUID userId) {
        return User.createSocialMember(
                "member",
                "회원",
                LocalDate.of(1990, 1, 1),
                "01012345678",
                "member@example.com",
                false,
                ProviderType.KAKAO,
                "provider-id",
                "provider@example.com"
        ).toBuilder()
                .userId(userId)
                .build();
    }
}
