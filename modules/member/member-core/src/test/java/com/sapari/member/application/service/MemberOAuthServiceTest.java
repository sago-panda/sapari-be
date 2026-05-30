package com.sapari.member.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import tools.jackson.databind.ObjectMapper;

import com.sapari.common.web.security.jwt.JwtProperties;
import com.sapari.common.web.security.jwt.JwtTokenProvider;
import com.sapari.global.time.TimeProvider;
import com.sapari.member.application.dto.SocialSignupInfo;
import com.sapari.member.command.MemberOAuthCommand;
import com.sapari.member.domain.exception.MemberErrorCode;
import com.sapari.member.domain.exception.MemberException;
import com.sapari.member.infrastructure.redis.SocialLoginCodeRedisRepository;
import com.sapari.member.infrastructure.redis.SocialSignupRedisRepository;
import com.sapari.member.result.MemberOAuthResult;
import com.sapari.member.result.MemberOAuthResultType;
import com.sapari.member.result.SocialLoginTokenResult;
import com.sapari.user.domain.model.ProviderType;
import com.sapari.user.domain.model.User;
import com.sapari.user.domain.model.UserGender;
import com.sapari.user.domain.model.UserRole;
import com.sapari.user.domain.repository.UserRepository;
import com.sapari.user.infrastructure.security.redis.RefreshTokenRedisRepository;

@DisplayName("구매자 OAuth 서비스 테스트")
class MemberOAuthServiceTest {

    private static final String SECRET = "test-secret-key-for-member-jwt-32bytes";

    private final UserRepository userRepository = mock(UserRepository.class);
    private final SocialSignupRedisRepository socialSignupRedisRepository =
            mock(SocialSignupRedisRepository.class);
    private final SocialLoginCodeRedisRepository socialLoginCodeRedisRepository =
            mock(SocialLoginCodeRedisRepository.class);
    private final JwtTokenProvider jwtTokenProvider = new JwtTokenProvider(
            new JwtProperties("member-oauth-test", SECRET, 3600L, 1209600L),
            timeProvider()
    );
    private final RefreshTokenRedisRepository refreshTokenRedisRepository =
            mock(RefreshTokenRedisRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MemberOAuthService memberOAuthService = new MemberOAuthService(
            userRepository,
            socialSignupRedisRepository,
            socialLoginCodeRedisRepository,
            jwtTokenProvider,
            refreshTokenRedisRepository,
            objectMapper
    );

    @Test
    @DisplayName("기존 회원이면 임시 로그인 code와 토큰 정보를 저장한다")
    void handleOAuthSuccessCreatesLoginCodeAndStoresTokenInfoWhenUserExists() throws Exception {
        // given
        UUID userId = UUID.randomUUID();
        User user = createMember(userId);
        when(userRepository.findByProviderAndProviderId(ProviderType.NAVER, "naver-id"))
                .thenReturn(Optional.of(user));

        // when
        MemberOAuthResult result = memberOAuthService.handleOAuthSuccess(oAuthCommand());

        // then
        assertThat(result.type()).isEqualTo(MemberOAuthResultType.LOGIN_SUCCESS);
        assertThat(result.loginCode()).isNotBlank();
        assertThat(result.signupSid()).isNull();
        verify(refreshTokenRedisRepository).save(eq(userId), any(String.class));

        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(socialLoginCodeRedisRepository).save(codeCaptor.capture(), valueCaptor.capture());
        assertThat(codeCaptor.getValue()).isEqualTo(result.loginCode());

        SocialLoginTokenResult tokenResult =
                objectMapper.readValue(valueCaptor.getValue(), SocialLoginTokenResult.class);
        assertThat(tokenResult.userId()).isEqualTo(userId);
        assertThat(tokenResult.accessToken()).isNotBlank();
        assertThat(tokenResult.refreshToken()).isNotBlank();
        verifyNoInteractions(socialSignupRedisRepository);
    }

    @Test
    @DisplayName("신규 회원이면 소셜 회원가입 정보를 저장하고 signup sid를 반환한다")
    void handleOAuthSuccessStoresSocialSignupInfoWhenUserDoesNotExist() throws Exception {
        // given
        when(userRepository.findByProviderAndProviderId(ProviderType.NAVER, "naver-id"))
                .thenReturn(Optional.empty());

        // when
        MemberOAuthResult result = memberOAuthService.handleOAuthSuccess(oAuthCommand());

        // then
        assertThat(result.type()).isEqualTo(MemberOAuthResultType.SIGNUP_REQUIRED);
        assertThat(result.signupSid()).isNotBlank();
        assertThat(result.loginCode()).isNull();

        ArgumentCaptor<String> sidCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(socialSignupRedisRepository).save(sidCaptor.capture(), valueCaptor.capture());
        assertThat(sidCaptor.getValue()).isEqualTo(result.signupSid());

        SocialSignupInfo signupInfo =
                objectMapper.readValue(valueCaptor.getValue(), SocialSignupInfo.class);
        assertThat(signupInfo.provider()).isEqualTo(ProviderType.NAVER);
        assertThat(signupInfo.providerId()).isEqualTo("naver-id");
        assertThat(signupInfo.providerEmail()).isEqualTo("member@naver.com");
        assertThat(signupInfo.name()).isEqualTo("member-name");
        assertThat(signupInfo.nickname()).isEqualTo("member-nickname");
        assertThat(signupInfo.phoneNumber()).isEqualTo("01012345678");
        assertThat(signupInfo.profileImageUrl()).isEqualTo("https://image.example/naver.png");
        assertThat(signupInfo.gender()).isEqualTo(UserGender.MALE);
        assertThat(signupInfo.birthDate()).isEqualTo(LocalDate.of(2000, 1, 1));
        verifyNoInteractions(refreshTokenRedisRepository, socialLoginCodeRedisRepository);
    }

    @Test
    @DisplayName("지원하지 않는 provider면 OAuth 처리에 실패한다")
    void handleOAuthSuccessThrowsExceptionWhenProviderIsUnsupported() {
        MemberOAuthCommand command = new MemberOAuthCommand(
                "GOOGLE",
                "google-id",
                "member@gmail.com",
                "member-name",
                "member-nickname",
                "01012345678",
                null,
                UserGender.MALE.name(),
                LocalDate.of(2000, 1, 1)
        );

        assertThatThrownBy(() -> memberOAuthService.handleOAuthSuccess(command))
                .isInstanceOfSatisfying(MemberException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(MemberErrorCode.INVALID_OAUTH_PROVIDER)
                );
    }

    @Test
    @DisplayName("기존 회원이 구매자가 아니면 OAuth 로그인에 실패한다")
    void handleOAuthSuccessThrowsExceptionWhenExistingUserIsNotMember() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findByProviderAndProviderId(ProviderType.NAVER, "naver-id"))
                .thenReturn(Optional.of(createSeller(userId)));

        assertThatThrownBy(() -> memberOAuthService.handleOAuthSuccess(oAuthCommand()))
                .isInstanceOfSatisfying(MemberException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(MemberErrorCode.USER_NOT_FOUND)
                );
    }

    private MemberOAuthCommand oAuthCommand() {
        return new MemberOAuthCommand(
                ProviderType.NAVER.name(),
                "naver-id",
                "member@naver.com",
                "member-name",
                "member-nickname",
                "01012345678",
                "https://image.example/naver.png",
                UserGender.MALE.name(),
                LocalDate.of(2000, 1, 1)
        );
    }

    private TimeProvider timeProvider() {
        return new TimeProvider(Clock.fixed(Instant.now(), ZoneOffset.UTC));
    }

    private User createMember(UUID userId) {
        return User.createSocialMember(
                "member",
                "구매자",
                LocalDate.of(2000, 1, 1),
                UserGender.MALE,
                "01012345678",
                "member@example.com",
                true,
                ProviderType.NAVER,
                "naver-id",
                "member@naver.com",
                providerCreatedAt(),
                providerCreatedAt()
        ).toBuilder()
                .userId(userId)
                .build();
    }

    private Instant providerCreatedAt() {
        return Instant.parse("2025-01-01T00:00:00Z");
    }

    private User createSeller(UUID userId) {
        return User.createSeller(
                "seller",
                "판매자",
                LocalDate.of(1990, 1, 1),
                "01099998888",
                "seller@example.com",
                true,
                providerCreatedAt()
        ).toBuilder()
                .userId(userId)
                .build();
    }
}
