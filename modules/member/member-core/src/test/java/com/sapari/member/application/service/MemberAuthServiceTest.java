package com.sapari.member.application.service;

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

import tools.jackson.databind.ObjectMapper;

import com.sapari.common.web.security.jwt.JwtProperties;
import com.sapari.common.web.security.jwt.JwtSubject;
import com.sapari.common.web.security.jwt.JwtTokenProvider;
import com.sapari.common.web.security.jwt.JwtTokenType;
import com.sapari.member.application.dto.SocialSignupInfo;
import com.sapari.member.command.MemberLogoutCommand;
import com.sapari.member.command.MemberMeUpdateCommand;
import com.sapari.member.command.SocialSignupCommand;
import com.sapari.member.domain.exception.MemberErrorCode;
import com.sapari.member.domain.exception.MemberException;
import com.sapari.member.infrastructure.redis.SocialLoginCodeRedisRepository;
import com.sapari.member.infrastructure.redis.SocialSignupRedisRepository;
import com.sapari.member.result.MemberMeResult;
import com.sapari.member.result.MemberTokenReissueResult;
import com.sapari.member.result.SocialLoginTokenResult;
import com.sapari.member.result.SocialSignupResult;
import com.sapari.user.domain.model.ProviderType;
import com.sapari.user.domain.model.User;
import com.sapari.user.domain.model.UserRole;
import com.sapari.user.domain.repository.UserRepository;
import com.sapari.user.infrastructure.security.redis.AccessTokenBlacklistRedisRepository;
import com.sapari.user.infrastructure.security.redis.RefreshTokenRedisRepository;

@DisplayName("구매자 인증 서비스 테스트")
class MemberAuthServiceTest {

    private static final String SECRET = "test-secret-key-for-member-jwt-32bytes";
    private static final String SIGNUP_SID = "signup-session-id";
    private static final String TEMPORARY_LOGIN_CODE = "temporary-login-code";
    private static final String EMAIL = "member@example.com";

    private final SocialSignupRedisRepository socialSignupRedisRepository =
            mock(SocialSignupRedisRepository.class);
    private final SocialLoginCodeRedisRepository socialLoginCodeRedisRepository =
            mock(SocialLoginCodeRedisRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final JwtTokenProvider jwtTokenProvider = new JwtTokenProvider(
            new JwtProperties("member-test", SECRET, 3600L, 1209600L)
    );
    private final RefreshTokenRedisRepository refreshTokenRedisRepository =
            mock(RefreshTokenRedisRepository.class);
    private final AccessTokenBlacklistRedisRepository accessTokenBlacklistRedisRepository =
            mock(AccessTokenBlacklistRedisRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MemberAuthService memberAuthService = new MemberAuthService(
            socialSignupRedisRepository,
            socialLoginCodeRedisRepository,
            userRepository,
            jwtTokenProvider,
            refreshTokenRedisRepository,
            accessTokenBlacklistRedisRepository,
            objectMapper
    );

    @Test
    @DisplayName("소셜 회원가입 완료 시 User를 저장하고 토큰을 발급한다")
    void completeSocialSignupCreatesUserAndIssuesTokens() throws Exception {
        // given
        UUID userId = UUID.randomUUID();
        when(socialSignupRedisRepository.findBySid(SIGNUP_SID))
                .thenReturn(Optional.of(objectMapper.writeValueAsString(socialSignupInfo())));
        when(userRepository.save(any(User.class))).thenAnswer(invocation ->
                ((User) invocation.getArgument(0)).toBuilder()
                        .userId(userId)
                        .build()
        );

        // when
        SocialSignupResult result = memberAuthService.completeSocialSignup(SIGNUP_SID, signupCommand());

        // then
        assertThat(result.userId()).isEqualTo(userId);
        assertThat(jwtTokenProvider.getTokenType(result.accessToken())).isEqualTo(JwtTokenType.ACCESS);
        assertThat(jwtTokenProvider.getTokenType(result.refreshToken())).isEqualTo(JwtTokenType.REFRESH);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().role()).isEqualTo(UserRole.USER);
        assertThat(userCaptor.getValue().provider()).isEqualTo(ProviderType.NAVER);
        assertThat(userCaptor.getValue().providerId()).isEqualTo("naver-id");
        assertThat(userCaptor.getValue().email()).isEqualTo(EMAIL);

        verify(socialSignupRedisRepository).delete(SIGNUP_SID);
        verify(refreshTokenRedisRepository).save(userId, result.refreshToken());
    }

    @Test
    @DisplayName("회원가입 sid가 없으면 소셜 회원가입에 실패한다")
    void completeSocialSignupThrowsExceptionWhenSignupSidIsMissing() {
        assertThatThrownBy(() -> memberAuthService.completeSocialSignup(null, signupCommand()))
                .isInstanceOfSatisfying(MemberException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(MemberErrorCode.INVALID_SIGNUP_SESSION)
                );

        verifyNoInteractions(userRepository, refreshTokenRedisRepository);
    }

    @Test
    @DisplayName("임시 로그인 code가 있으면 저장된 토큰 정보를 반환하고 code를 삭제한다")
    void exchangeTemporaryLoginCodeReturnsTokenInfoAndDeletesCode() throws Exception {
        // given
        UUID userId = UUID.randomUUID();
        SocialLoginTokenResult tokenResult =
                new SocialLoginTokenResult(userId, "access-token", "refresh-token");
        when(socialLoginCodeRedisRepository.findByCode(TEMPORARY_LOGIN_CODE))
                .thenReturn(Optional.of(objectMapper.writeValueAsString(tokenResult)));

        // when
        SocialLoginTokenResult result =
                memberAuthService.exchangeTemporaryLoginCode(TEMPORARY_LOGIN_CODE);

        // then
        assertThat(result).isEqualTo(tokenResult);
        verify(socialLoginCodeRedisRepository).delete(TEMPORARY_LOGIN_CODE);
    }

    @Test
    @DisplayName("임시 로그인 code가 없으면 교환에 실패한다")
    void exchangeTemporaryLoginCodeThrowsExceptionWhenCodeIsMissing() {
        assertThatThrownBy(() -> memberAuthService.exchangeTemporaryLoginCode(" "))
                .isInstanceOfSatisfying(MemberException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(MemberErrorCode.INVALID_LOGIN_CODE)
                );
    }

    @Test
    @DisplayName("저장된 Refresh Token과 일치하면 Access Token을 재발급한다")
    void reissueAccessTokenReturnsNewAccessTokenWhenRefreshTokenMatches() {
        // given
        UUID userId = UUID.randomUUID();
        User member = createMember(userId);
        String refreshToken =
                jwtTokenProvider.createRefreshToken(new JwtSubject(userId, UserRole.USER.name()));
        when(userRepository.findById(userId)).thenReturn(Optional.of(member));
        when(refreshTokenRedisRepository.findByUserId(userId)).thenReturn(Optional.of(refreshToken));

        // when
        MemberTokenReissueResult result = memberAuthService.reissueAccessToken(refreshToken);

        // then
        assertThat(result.userId()).isEqualTo(userId);
        assertThat(jwtTokenProvider.getTokenType(result.accessToken())).isEqualTo(JwtTokenType.ACCESS);
    }

    @Test
    @DisplayName("판매자 Refresh Token이면 구매자 토큰 재발급에 실패한다")
    void reissueAccessTokenThrowsExceptionWhenTokenBelongsToSeller() {
        // given
        UUID userId = UUID.randomUUID();
        String refreshToken =
                jwtTokenProvider.createRefreshToken(new JwtSubject(userId, UserRole.SELLER.name()));
        when(userRepository.findById(userId)).thenReturn(Optional.of(createSeller(userId)));

        // when, then
        assertThatThrownBy(() -> memberAuthService.reissueAccessToken(refreshToken))
                .isInstanceOfSatisfying(MemberException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(MemberErrorCode.INVALID_REFRESH_TOKEN)
                );
    }

    @Test
    @DisplayName("로그아웃 시 Refresh Token을 삭제하고 Access Token을 blacklist에 저장한다")
    void logoutDeletesRefreshTokenAndBlacklistsAccessToken() {
        // given
        UUID userId = UUID.randomUUID();
        String accessToken =
                jwtTokenProvider.createAccessToken(new JwtSubject(userId, UserRole.USER.name()));

        // when
        memberAuthService.logout(new MemberLogoutCommand(userId, accessToken));

        // then
        verify(refreshTokenRedisRepository).delete(userId);

        ArgumentCaptor<Duration> durationCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(accessTokenBlacklistRedisRepository).save(eq(accessToken), durationCaptor.capture());
        assertThat(durationCaptor.getValue()).isPositive();
    }

    @Test
    @DisplayName("내정보 조회 시 구매자가 아니면 실패한다")
    void getMyInfoThrowsExceptionWhenUserIsNotMember() {
        // given
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(createSeller(userId)));

        // when, then
        assertThatThrownBy(() -> memberAuthService.getMyInfo(userId))
                .isInstanceOfSatisfying(MemberException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(MemberErrorCode.USER_NOT_FOUND)
                );
    }

    @Test
    @DisplayName("내정보 수정 시 중복을 검증하고 구매자 프로필을 저장한다")
    void updateMyInfoUpdatesMemberProfile() {
        // given
        UUID userId = UUID.randomUUID();
        MemberMeUpdateCommand command = new MemberMeUpdateCommand(
                userId,
                "updated",
                "수정자",
                LocalDate.of(1999, 12, 31),
                "01087654321",
                "profile/new.png",
                "updated@example.com",
                false
        );
        when(userRepository.findById(userId)).thenReturn(Optional.of(createMember(userId)));
        when(userRepository.existsByPhoneNumberAndUserIdNot("01087654321", userId)).thenReturn(false);
        when(userRepository.existsByEmailAndUserIdNot("updated@example.com", userId)).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        MemberMeResult result = memberAuthService.updateMyInfo(command);

        // then
        assertThat(result.userId()).isEqualTo(userId);
        assertThat(result.nickname()).isEqualTo("updated");
        assertThat(result.phoneNumber()).isEqualTo("01087654321");
        assertThat(result.email()).isEqualTo("updated@example.com");
        assertThat(result.role()).isEqualTo(UserRole.USER.name());
        verify(userRepository).save(any(User.class));
    }

    @Test
    @DisplayName("다른 사용자의 이메일과 중복되면 내정보 수정에 실패한다")
    void updateMyInfoThrowsExceptionWhenEmailIsDuplicated() {
        // given
        UUID userId = UUID.randomUUID();
        MemberMeUpdateCommand command = new MemberMeUpdateCommand(
                userId,
                "updated",
                "수정자",
                LocalDate.of(1999, 12, 31),
                "01087654321",
                "profile/new.png",
                "updated@example.com",
                true
        );
        when(userRepository.findById(userId)).thenReturn(Optional.of(createMember(userId)));
        when(userRepository.existsByPhoneNumberAndUserIdNot("01087654321", userId)).thenReturn(false);
        when(userRepository.existsByEmailAndUserIdNot("updated@example.com", userId)).thenReturn(true);

        // when, then
        assertThatThrownBy(() -> memberAuthService.updateMyInfo(command))
                .isInstanceOfSatisfying(MemberException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(MemberErrorCode.DUPLICATED_EMAIL)
                );
    }

    @Test
    @DisplayName("이메일 중복 여부를 조회한다")
    void isEmailDuplicatedReturnsRepositoryResult() {
        when(userRepository.existsByEmail(EMAIL)).thenReturn(true);

        assertThat(memberAuthService.isEmailDuplicated(EMAIL)).isTrue();
    }

    @Test
    @DisplayName("전화번호 중복 여부를 조회한다")
    void isPhoneNumberDuplicatedReturnsRepositoryResult() {
        when(userRepository.existsByPhoneNumber("01012345678")).thenReturn(true);

        assertThat(memberAuthService.isPhoneNumberDuplicated("01012345678")).isTrue();
    }

    private SocialSignupCommand signupCommand() {
        return new SocialSignupCommand(
                "01012345678",
                EMAIL,
                "member",
                "구매자",
                LocalDate.of(2000, 1, 1),
                true
        );
    }

    private SocialSignupInfo socialSignupInfo() {
        return new SocialSignupInfo(
                ProviderType.NAVER,
                "naver-id",
                "provider@example.com",
                "소셜이름",
                "https://image.example/profile.png"
        );
    }

    private User createMember(UUID userId) {
        return User.createSocialMember(
                "member",
                "구매자",
                LocalDate.of(2000, 1, 1),
                "01012345678",
                EMAIL,
                true,
                ProviderType.NAVER,
                "naver-id",
                "provider@example.com"
        ).toBuilder()
                .userId(userId)
                .build();
    }

    private User createSeller(UUID userId) {
        return User.createSeller(
                "seller",
                "판매자",
                LocalDate.of(1990, 1, 1),
                "01099998888",
                "seller@example.com",
                true
        ).toBuilder()
                .userId(userId)
                .build();
    }
}
