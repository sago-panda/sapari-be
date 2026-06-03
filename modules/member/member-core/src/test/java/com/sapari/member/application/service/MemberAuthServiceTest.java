package com.sapari.member.application.service;

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
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import tools.jackson.databind.ObjectMapper;

import com.sapari.common.web.security.jwt.JwtProperties;
import com.sapari.common.web.security.jwt.JwtSubject;
import com.sapari.common.web.security.jwt.JwtTokenClaims;
import com.sapari.common.web.security.jwt.JwtTokenProvider;
import com.sapari.common.web.security.jwt.JwtTokenType;
import com.sapari.global.time.TimeProvider;
import com.sapari.member.application.dto.SocialSignupInfo;
import com.sapari.member.command.MemberLogoutCommand;
import com.sapari.member.command.MemberNicknameUpdateCommand;
import com.sapari.member.command.SocialSignupCommand;
import com.sapari.member.domain.exception.MemberErrorCode;
import com.sapari.member.domain.exception.MemberException;
import com.sapari.member.infrastructure.redis.SocialLoginCodeRedisRepository;
import com.sapari.member.infrastructure.redis.SocialSignupRedisRepository;
import com.sapari.member.result.MemberNicknameUpdateResult;
import com.sapari.member.result.MemberTokenReissueResult;
import com.sapari.member.result.SocialSignupInfoResult;
import com.sapari.member.result.SocialLoginTokenResult;
import com.sapari.member.result.SocialSignupResult;
import com.sapari.user.domain.model.ProviderType;
import com.sapari.user.domain.model.User;
import com.sapari.user.domain.model.UserGender;
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
    private static final Instant NOW = Instant.now();

    private final SocialSignupRedisRepository socialSignupRedisRepository =
            mock(SocialSignupRedisRepository.class);
    private final SocialLoginCodeRedisRepository socialLoginCodeRedisRepository =
            mock(SocialLoginCodeRedisRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final JwtTokenProvider jwtTokenProvider = new JwtTokenProvider(
            new JwtProperties("member-test", SECRET, 3600L, 1209600L),
            timeProvider()
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
            timeProvider(),
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
        JwtTokenClaims accessClaims = jwtTokenProvider.parseToken(result.accessToken());
        JwtTokenClaims refreshClaims = jwtTokenProvider.parseToken(result.refreshToken());
        assertThat(accessClaims.tokenType()).isEqualTo(JwtTokenType.ACCESS);
        assertThat(accessClaims.nickname()).isEqualTo("member");
        assertThat(accessClaims.email()).isEqualTo(EMAIL);
        assertThat(refreshClaims.tokenType()).isEqualTo(JwtTokenType.REFRESH);
        assertThat(refreshClaims.nickname()).isNull();
        assertThat(refreshClaims.email()).isNull();

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().role()).isEqualTo(UserRole.USER);
        assertThat(userCaptor.getValue().provider()).isEqualTo(ProviderType.NAVER);
        assertThat(userCaptor.getValue().providerId()).isEqualTo("naver-id");
        assertThat(userCaptor.getValue().email()).isEqualTo(EMAIL);
        assertThat(userCaptor.getValue().gender()).isEqualTo(UserGender.FEMALE);
        assertThat(userCaptor.getValue().nicknameChangedAt()).isEqualTo(NOW);

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
    @DisplayName("회원가입 sid로 소셜 가입 기본 정보를 조회한다")
    void getSocialSignupInfoReturnsSocialSignupInfo() throws Exception {
        // given
        when(socialSignupRedisRepository.findBySid(SIGNUP_SID))
                .thenReturn(Optional.of(objectMapper.writeValueAsString(socialSignupInfo())));

        // when
        SocialSignupInfoResult result = memberAuthService.getSocialSignupInfo(SIGNUP_SID);

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
    @DisplayName("회원가입 sid가 없으면 소셜 가입 기본 정보 조회에 실패한다")
    void getSocialSignupInfoThrowsExceptionWhenSignupSidIsMissing() {
        assertThatThrownBy(() -> memberAuthService.getSocialSignupInfo(null))
                .isInstanceOfSatisfying(MemberException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(MemberErrorCode.INVALID_SIGNUP_SESSION)
                );
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
                jwtTokenProvider.createRefreshToken(jwtSubject(userId, UserRole.USER.name()));
        when(userRepository.findById(userId)).thenReturn(Optional.of(member));
        when(refreshTokenRedisRepository.findByUserId(userId)).thenReturn(Optional.of(refreshToken));

        // when
        MemberTokenReissueResult result = memberAuthService.reissueAccessToken(refreshToken);

        // then
        assertThat(result.userId()).isEqualTo(userId);
        JwtTokenClaims accessClaims = jwtTokenProvider.parseToken(result.accessToken());
        assertThat(accessClaims.tokenType()).isEqualTo(JwtTokenType.ACCESS);
        assertThat(accessClaims.nickname()).isEqualTo("member");
        assertThat(accessClaims.email()).isEqualTo(EMAIL);
    }

    @Test
    @DisplayName("판매자 Refresh Token이면 구매자 토큰 재발급에 실패한다")
    void reissueAccessTokenThrowsExceptionWhenTokenBelongsToSeller() {
        // given
        UUID userId = UUID.randomUUID();
        String refreshToken =
                jwtTokenProvider.createRefreshToken(jwtSubject(userId, UserRole.SELLER.name()));
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
                jwtTokenProvider.createAccessToken(jwtSubject(userId, UserRole.USER.name()));

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
    @DisplayName("30일이 지난 뒤 닉네임 수정 시 중복을 검증하고 닉네임만 저장한다")
    void updateNicknameUpdatesMemberNickname() {
        // given
        UUID userId = UUID.randomUUID();
        MemberNicknameUpdateCommand command = new MemberNicknameUpdateCommand(
                userId,
                "updated"
        );
        when(userRepository.findById(userId)).thenReturn(Optional.of(createMember(userId)));
        when(userRepository.existsByNickname("updated")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        MemberNicknameUpdateResult result = memberAuthService.updateNickname(command);
        JwtTokenClaims accessClaims = jwtTokenProvider.parseToken(result.accessToken());

        // then
        assertThat(result.member().userId()).isEqualTo(userId);
        assertThat(result.member().nickname()).isEqualTo("updated");
        assertThat(result.member().name()).isEqualTo("구매자");
        assertThat(result.member().birthDate()).isEqualTo(LocalDate.of(2000, 1, 1));
        assertThat(result.member().phoneNumber()).isEqualTo("01012345678");
        assertThat(result.member().email()).isEqualTo(EMAIL);
        assertThat(result.member().role()).isEqualTo(UserRole.USER.name());
        assertThat(accessClaims.tokenType()).isEqualTo(JwtTokenType.ACCESS);
        assertThat(accessClaims.nickname()).isEqualTo("updated");
        assertThat(accessClaims.email()).isEqualTo(EMAIL);
        verify(userRepository).existsByNickname("updated");
        verify(userRepository).save(any(User.class));
        verify(refreshTokenRedisRepository, never()).save(eq(userId), any(String.class));
    }

    @Test
    @DisplayName("가입 후 30일이 지나지 않았으면 닉네임 수정에 실패한다")
    void updateNicknameThrowsExceptionWhenChangedWithinThirtyDays() {
        // given
        UUID userId = UUID.randomUUID();
        MemberNicknameUpdateCommand command = new MemberNicknameUpdateCommand(
                userId,
                "updated"
        );
        when(userRepository.findById(userId)).thenReturn(Optional.of(createMember(userId, NOW.minus(Duration.ofDays(1)))));
        when(userRepository.existsByNickname("updated")).thenReturn(false);

        // when, then
        assertThatThrownBy(() -> memberAuthService.updateNickname(command))
                .isInstanceOfSatisfying(MemberException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(MemberErrorCode.NICKNAME_CHANGE_RESTRICTED)
                );
        verify(userRepository).existsByNickname("updated");
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("같은 닉네임이면 닉네임 중복으로 실패한다")
    void updateNicknameThrowsExceptionWhenNicknameIsSame() {
        // given
        UUID userId = UUID.randomUUID();
        MemberNicknameUpdateCommand command = new MemberNicknameUpdateCommand(
                userId,
                "member"
        );
        when(userRepository.findById(userId)).thenReturn(Optional.of(createMember(userId, NOW)));
        when(userRepository.existsByNickname("member")).thenReturn(true);

        // when, then
        assertThatThrownBy(() -> memberAuthService.updateNickname(command))
                .isInstanceOfSatisfying(MemberException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(MemberErrorCode.DUPLICATED_NICKNAME)
                );
        verify(userRepository).existsByNickname("member");
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("이미 존재하는 닉네임이면 닉네임 수정에 실패한다")
    void updateNicknameThrowsExceptionWhenNicknameIsDuplicated() {
        // given
        UUID userId = UUID.randomUUID();
        MemberNicknameUpdateCommand command = new MemberNicknameUpdateCommand(
                userId,
                "updated"
        );
        when(userRepository.findById(userId)).thenReturn(Optional.of(createMember(userId)));
        when(userRepository.existsByNickname("updated")).thenReturn(true);

        // when, then
        assertThatThrownBy(() -> memberAuthService.updateNickname(command))
                .isInstanceOfSatisfying(MemberException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(MemberErrorCode.DUPLICATED_NICKNAME)
                );
    }

    @Test
    @DisplayName("저장 중 닉네임 unique 충돌이 발생하면 닉네임 중복으로 실패한다")
    void updateNicknameThrowsExceptionWhenNicknameSaveConflicts() {
        // given
        UUID userId = UUID.randomUUID();
        MemberNicknameUpdateCommand command = new MemberNicknameUpdateCommand(
                userId,
                "updated"
        );
        when(userRepository.findById(userId)).thenReturn(Optional.of(createMember(userId)));
        when(userRepository.existsByNickname("updated")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenThrow(new DataIntegrityViolationException("duplicated"));

        // when, then
        assertThatThrownBy(() -> memberAuthService.updateNickname(command))
                .isInstanceOfSatisfying(MemberException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(MemberErrorCode.DUPLICATED_NICKNAME)
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

    @Test
    @DisplayName("닉네임 중복 여부를 조회한다")
    void isNicknameDuplicatedReturnsRepositoryResult() {
        when(userRepository.existsByNickname("member")).thenReturn(true);

        assertThat(memberAuthService.isNicknameDuplicated("member")).isTrue();
    }

    private SocialSignupCommand signupCommand() {
        return new SocialSignupCommand(
                "01012345678",
                EMAIL,
                "member",
                "구매자",
                LocalDate.of(2000, 1, 1),
                UserGender.FEMALE.name(),
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
        return new JwtSubject(userId, role, "member", EMAIL);
    }

    private User createMember(UUID userId) {
        return createMember(userId, providerCreatedAt());
    }

    private User createMember(UUID userId, Instant nicknameChangedAt) {
        return User.createSocialMember(
                "member",
                "구매자",
                LocalDate.of(2000, 1, 1),
                UserGender.MALE,
                "01012345678",
                EMAIL,
                true,
                ProviderType.NAVER,
                "naver-id",
                "provider@example.com",
                providerCreatedAt(),
                nicknameChangedAt
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
