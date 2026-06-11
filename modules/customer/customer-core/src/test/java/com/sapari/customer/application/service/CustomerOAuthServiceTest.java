package com.sapari.customer.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.mockito.ArgumentCaptor;

import tools.jackson.databind.ObjectMapper;

import com.sapari.common.securityjwt.jwt.JwtProperties;
import com.sapari.common.securityjwt.jwt.JwtTokenClaims;
import com.sapari.common.securityjwt.jwt.JwtTokenProvider;
import com.sapari.common.securityjwt.jwt.JwtTokenType;
import com.sapari.global.time.TimeProvider;
import com.sapari.customer.application.dto.SocialSignupInfo;
import com.sapari.customer.command.CustomerOAuthCommand;
import com.sapari.customer.domain.exception.CustomerErrorCode;
import com.sapari.customer.domain.exception.CustomerException;
<<<<<<< HEAD
import com.sapari.customer.infrastructure.redis.SocialLoginCodeRedisRepository;
import com.sapari.customer.infrastructure.redis.SocialSignupRedisRepository;
import com.sapari.customer.view.CustomerOAuthResult;
import com.sapari.customer.view.CustomerOAuthResultType;
import com.sapari.customer.view.SocialLoginTokenResult;
=======
import com.sapari.customer.domain.repository.SocialLoginCodeRepository;
import com.sapari.customer.domain.repository.SocialSignupRepository;
import com.sapari.customer.result.CustomerOAuthResult;
import com.sapari.customer.result.CustomerOAuthResultType;
import com.sapari.customer.result.SocialLoginTokenResult;
>>>>>>> dev
import com.sapari.common.securityjwt.store.RefreshTokenStore;
import com.sapari.user.model.ProviderType;
import com.sapari.user.model.UserGender;
import com.sapari.user.model.UserGrade;
import com.sapari.user.model.UserRole;
import com.sapari.user.model.UserStatus;
import com.sapari.user.port.UserAccountUseCase;
import com.sapari.user.view.UserView;

@DisplayName("구매자 OAuth 서비스 테스트")
class CustomerOAuthServiceTest {

    private static final String SECRET = "test-secret-key-for-customer-jwt-32bytes";

    private final UserAccountUseCase userAccountUseCase = mock(UserAccountUseCase.class);
    private final SocialSignupRepository socialSignupRepository =
            mock(SocialSignupRepository.class);
    private final SocialLoginCodeRepository socialLoginCodeRepository =
            mock(SocialLoginCodeRepository.class);
    private final JwtTokenProvider jwtTokenProvider = new JwtTokenProvider(
            new JwtProperties("customer-oauth-test", SECRET, 3600L, 1209600L),
            timeProvider()
    );
    private final RefreshTokenStore refreshTokenStore =
            mock(RefreshTokenStore.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CustomerOAuthService customerOAuthService = new CustomerOAuthService(
            userAccountUseCase,
            socialSignupRepository,
            socialLoginCodeRepository,
            jwtTokenProvider,
            refreshTokenStore,
            timeProvider(),
            objectMapper
    );

    @Test
    @DisplayName("기존 고객이면 임시 로그인 code와 토큰 정보를 저장한다")
    void handleOAuthSuccessCreatesLoginCodeAndStoresTokenInfoWhenUserExists() throws Exception {
        // given
        UUID userId = UUID.randomUUID();
        when(userAccountUseCase.findBySocialAccount(ProviderType.NAVER, "naver-id"))
                .thenReturn(Optional.of(customerView(userId)));

        // when
        CustomerOAuthResult result = customerOAuthService.handleOAuthSuccess(oAuthCommand());

        // then
        assertThat(result.type()).isEqualTo(CustomerOAuthResultType.LOGIN_SUCCESS);
        assertThat(result.loginCode()).isNotBlank();
        assertThat(result.signupSid()).isNull();
        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(socialLoginCodeRepository).save(codeCaptor.capture(), valueCaptor.capture());
        assertThat(codeCaptor.getValue()).isEqualTo(result.loginCode());

        SocialLoginTokenResult tokenResult =
                objectMapper.readValue(valueCaptor.getValue(), SocialLoginTokenResult.class);
        assertThat(tokenResult.userId()).isEqualTo(userId);
        assertThat(tokenResult.accessToken()).isNotBlank();
        assertThat(tokenResult.refreshToken()).isNotBlank();
        JwtTokenClaims accessClaims = jwtTokenProvider.parseToken(tokenResult.accessToken());
        JwtTokenClaims refreshClaims = jwtTokenProvider.parseToken(tokenResult.refreshToken());
        assertThat(accessClaims.tokenType()).isEqualTo(JwtTokenType.ACCESS);
        assertThat(accessClaims.sessionId()).isEqualTo(refreshClaims.sessionId());
        assertThat(accessClaims.tokenId()).isNotEqualTo(refreshClaims.tokenId());
        assertThat(accessClaims.nickname()).isEqualTo("customer");
        assertThat(accessClaims.email()).isEqualTo("customer@example.com");
        assertThat(refreshClaims.tokenType()).isEqualTo(JwtTokenType.REFRESH);
        assertThat(refreshClaims.nickname()).isNull();
        assertThat(refreshClaims.email()).isNull();
        verify(refreshTokenStore).save(
                eq(refreshClaims.sessionId()),
                eq(refreshClaims.tokenId()),
                any(Duration.class)
        );
        verifyNoInteractions(socialSignupRepository);
    }

    @Test
    @DisplayName("신규 고객이면 소셜 고객 가입 정보를 저장하고 signup sid를 반환한다")
    void handleOAuthSuccessStoresSocialSignupInfoWhenUserDoesNotExist() throws Exception {
        // given
        when(userAccountUseCase.findBySocialAccount(ProviderType.NAVER, "naver-id"))
                .thenReturn(Optional.empty());

        // when
        CustomerOAuthResult result = customerOAuthService.handleOAuthSuccess(oAuthCommand());

        // then
        assertThat(result.type()).isEqualTo(CustomerOAuthResultType.SIGNUP_REQUIRED);
        assertThat(result.signupSid()).isNotBlank();
        assertThat(result.loginCode()).isNull();

        ArgumentCaptor<String> sidCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(socialSignupRepository).save(sidCaptor.capture(), valueCaptor.capture());
        assertThat(sidCaptor.getValue()).isEqualTo(result.signupSid());

        SocialSignupInfo signupInfo =
                objectMapper.readValue(valueCaptor.getValue(), SocialSignupInfo.class);
        assertThat(signupInfo.provider()).isEqualTo(ProviderType.NAVER);
        assertThat(signupInfo.providerId()).isEqualTo("naver-id");
        assertThat(signupInfo.providerEmail()).isEqualTo("customer@naver.com");
        assertThat(signupInfo.name()).isEqualTo("customer-name");
        assertThat(signupInfo.nickname()).isEqualTo("customer-nickname");
        assertThat(signupInfo.phoneNumber()).isEqualTo("01012345678");
        assertThat(signupInfo.profileImageUrl()).isEqualTo("https://image.example/naver.png");
        assertThat(signupInfo.gender()).isEqualTo(UserGender.MALE);
        assertThat(signupInfo.birthDate()).isEqualTo(LocalDate.of(2000, 1, 1));
        verifyNoInteractions(refreshTokenStore, socialLoginCodeRepository);
    }

    @Test
    @DisplayName("지원하지 않는 provider면 OAuth 처리에 실패한다")
    void handleOAuthSuccessThrowsExceptionWhenProviderIsUnsupported() {
        CustomerOAuthCommand command = new CustomerOAuthCommand(
                "GOOGLE",
                "google-id",
                "customer@gmail.com",
                "customer-name",
                "customer-nickname",
                "01012345678",
                null,
                UserGender.MALE.name(),
                LocalDate.of(2000, 1, 1)
        );

        assertThatThrownBy(() -> customerOAuthService.handleOAuthSuccess(command))
                .isInstanceOfSatisfying(CustomerException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CustomerErrorCode.INVALID_OAUTH_PROVIDER)
                );
    }

    @Test
    @DisplayName("기존 고객이 구매자가 아니면 OAuth 로그인에 실패한다")
    void handleOAuthSuccessThrowsExceptionWhenExistingUserIsNotCustomer() {
        UUID userId = UUID.randomUUID();
        when(userAccountUseCase.findBySocialAccount(ProviderType.NAVER, "naver-id"))
                .thenReturn(Optional.of(sellerView(userId)));

        assertThatThrownBy(() -> customerOAuthService.handleOAuthSuccess(oAuthCommand()))
                .isInstanceOfSatisfying(CustomerException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CustomerErrorCode.USER_NOT_FOUND)
                );
    }

    private CustomerOAuthCommand oAuthCommand() {
        return new CustomerOAuthCommand(
                ProviderType.NAVER.name(),
                "naver-id",
                "customer@naver.com",
                "customer-name",
                "customer-nickname",
                "01012345678",
                "https://image.example/naver.png",
                UserGender.MALE.name(),
                LocalDate.of(2000, 1, 1)
        );
    }

    private TimeProvider timeProvider() {
        return new TimeProvider(Clock.fixed(Instant.now(), ZoneOffset.UTC));
    }

    private UserView customerView(UUID userId) {
        return new UserView(
                userId,
                UserRole.USER,
                UserStatus.ACTIVE,
                "customer",
                providerCreatedAt(),
                "구매자",
                LocalDate.of(2000, 1, 1),
                UserGender.MALE,
                "01012345678",
                null,
                "customer@example.com",
                UserGrade.BRONZE,
                0,
                true,
                ProviderType.NAVER,
                "naver-id",
                "customer@naver.com"
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
}
