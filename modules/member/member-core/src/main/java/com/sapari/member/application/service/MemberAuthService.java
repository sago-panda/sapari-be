package com.sapari.member.application.service;

import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

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
import com.sapari.member.port.MemberAuthUseCase;
import com.sapari.member.result.MemberMeResult;
import com.sapari.member.result.MemberNicknameUpdateResult;
import com.sapari.member.result.MemberTokenReissueResult;
import com.sapari.member.result.SocialSignupInfoResult;
import com.sapari.member.result.SocialLoginTokenResult;
import com.sapari.member.result.SocialSignupResult;
import com.sapari.user.command.RegisterSocialMemberCommand;
import com.sapari.common.web.security.AccessTokenBlacklist;
import com.sapari.common.web.security.RefreshTokenStore;
import com.sapari.user.model.UserGender;
import com.sapari.user.model.UserRole;
import com.sapari.user.port.UserAccountUseCase;
import com.sapari.user.view.UserView;

@Service
@RequiredArgsConstructor
public class MemberAuthService implements MemberAuthUseCase {

    private static final Duration NICKNAME_CHANGE_INTERVAL = Duration.ofDays(30);

    private final SocialSignupRedisRepository socialSignupRedisRepository;
    private final SocialLoginCodeRedisRepository socialLoginCodeRedisRepository;
    private final UserAccountUseCase userAccountUseCase;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenStore refreshTokenStore;
    private final AccessTokenBlacklist accessTokenBlacklist;
    private final TimeProvider timeProvider;
    private final ObjectMapper objectMapper;

    /**
     * signup sid로 임시 소셜 정보를 조회하고 추가정보를 합쳐 구매자 회원가입
     */
    @Override
    @Transactional
    public SocialSignupResult completeSocialSignup(String signupSid, SocialSignupCommand command) {
        SocialSignupInfo socialSignupInfo = findSocialSignupInfo(signupSid);

        try {
            UserView savedUser = userAccountUseCase.registerSocialMember(toRegisterCommand(command, socialSignupInfo));
            socialSignupRedisRepository.delete(signupSid);

            UUID sessionId = UUID.randomUUID();
            JwtSubject subject = toJwtSubject(savedUser, sessionId);
            String accessToken = jwtTokenProvider.createAccessToken(subject);
            String refreshToken = jwtTokenProvider.createRefreshToken(subject);
            refreshTokenStore.save(sessionId, refreshToken);

            return new SocialSignupResult(savedUser.userId(), accessToken, refreshToken);
        } catch (DataIntegrityViolationException e) {
            throw new MemberException(MemberErrorCode.DUPLICATED_SIGNUP_INFO, e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public SocialSignupInfoResult getSocialSignupInfo(String signupSid) {
        return toSocialSignupInfoResult(findSocialSignupInfo(signupSid));
    }

    /**
     * 임시 로그인 code를 최종 Access Token과 Refresh Token 정보로 교환
     */
    @Override
    @Transactional
    public SocialLoginTokenResult exchangeTemporaryLoginCode(String temporaryLoginCode) {
        if (temporaryLoginCode == null || temporaryLoginCode.isBlank()) {
            throw new MemberException(MemberErrorCode.INVALID_LOGIN_CODE);
        }

        SocialLoginTokenResult tokenResult = socialLoginCodeRedisRepository.findByCode(temporaryLoginCode)
                .map(this::readSocialLoginTokenResult)
                .orElseThrow(() -> new MemberException(MemberErrorCode.INVALID_LOGIN_CODE));

        socialLoginCodeRedisRepository.delete(temporaryLoginCode);

        return tokenResult;
    }

    /**
     * Refresh Token을 검증해 새 Access Token을 발급
     */
    @Override
    @Transactional(readOnly = true)
    public MemberTokenReissueResult reissueAccessToken(String refreshToken) {
        JwtTokenClaims claims = parseRefreshToken(refreshToken);

        UserView member = findRefreshTokenMember(claims.userId());
        // Refresh Token은 사용자 단위가 아니라 sid 세션 단위로 검증한다.
        String savedRefreshToken = refreshTokenStore.findBySessionId(claims.sessionId())
                .orElseThrow(() -> new MemberException(MemberErrorCode.INVALID_REFRESH_TOKEN));

        if (!savedRefreshToken.equals(refreshToken)) {
            throw new MemberException(MemberErrorCode.INVALID_REFRESH_TOKEN);
        }

        String accessToken = jwtTokenProvider.createAccessToken(toJwtSubject(member, claims.sessionId()));

        return new MemberTokenReissueResult(member.userId(), accessToken);
    }

    @Override
    @Transactional
    public void logout(MemberLogoutCommand command) {
        JwtTokenClaims claims = parseAccessToken(command.accessToken());
        validateAccessTokenOwner(claims, command.userId());

        // 로그아웃은 현재 sid의 Refresh Token을 제거하고 현재 Access Token jti만 폐기
        refreshTokenStore.deleteBySessionId(claims.sessionId());
        Duration remainingExpiration = getRemainingExpiration(claims);

        if (remainingExpiration.isZero() || remainingExpiration.isNegative()) {
            return;
        }

        accessTokenBlacklist.save(claims.tokenId(), remainingExpiration);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isPhoneNumberDuplicated(String phoneNumber) {
        return userAccountUseCase.existsByPhoneNumber(phoneNumber);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isEmailDuplicated(String email) {
        return userAccountUseCase.existsByEmail(email);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isNicknameDuplicated(String nickname) {
        return userAccountUseCase.existsByNickname(nickname);
    }

    @Override
    @Transactional(readOnly = true)
    public MemberMeResult getMyInfo(UUID userId) {
        return toMemberMeResult(findMember(userId));
    }

    @Override
    @Transactional
    public MemberNicknameUpdateResult updateNickname(MemberNicknameUpdateCommand command) {
        JwtTokenClaims accessClaims = parseAccessToken(command.accessToken());
        validateAccessTokenOwner(accessClaims, command.userId());

        UserView member = findMember(command.userId());

        validateDuplicatedNickname(command.nickname());

        Instant now = timeProvider.now();
        validateNicknameChangeAllowed(member, now);

        try {
            UserView savedMember = userAccountUseCase.changeNickname(command.userId(), command.nickname());
            // nickname이 바뀌었으므로 기존 access jti를 폐기하고 같은 sid로 새 Access Token을 발급
            blacklistAccessToken(accessClaims);
            String accessToken = jwtTokenProvider.createAccessToken(toJwtSubject(savedMember, accessClaims.sessionId()));

            return new MemberNicknameUpdateResult(toMemberMeResult(savedMember), accessToken);
        } catch (DataIntegrityViolationException e) {
            throw new MemberException(MemberErrorCode.DUPLICATED_NICKNAME, e);
        }
    }

    private RegisterSocialMemberCommand toRegisterCommand(SocialSignupCommand command, SocialSignupInfo socialSignupInfo) {
        return new RegisterSocialMemberCommand(
                command.nickname(),
                command.name(),
                command.birthDate(),
                UserGender.valueOf(command.gender()),
                command.phoneNumber(),
                command.email(),
                command.isMarketingAgreed(),
                socialSignupInfo.provider(),
                socialSignupInfo.providerId(),
                socialSignupInfo.providerEmail()
        );
    }

    // redis에 저장된 소셜정보 가지고 오는 메서드
    private SocialSignupInfo findSocialSignupInfo(String signupSid) {
        if (signupSid == null || signupSid.isBlank()) {
            throw new MemberException(MemberErrorCode.INVALID_SIGNUP_SESSION);
        }

        return socialSignupRedisRepository.findBySid(signupSid)
                .map(this::readSocialSignupInfo)
                .orElseThrow(() -> new MemberException(MemberErrorCode.INVALID_SIGNUP_SESSION));
    }

    private SocialSignupInfo readSocialSignupInfo(String json) {
        try {
            return objectMapper.readValue(json, SocialSignupInfo.class);
        } catch (Exception e) {
            throw new MemberException(MemberErrorCode.INVALID_SOCIAL_INFO, e);
        }
    }

    private SocialLoginTokenResult readSocialLoginTokenResult(String json) {
        try {
            return objectMapper.readValue(json, SocialLoginTokenResult.class);
        } catch (Exception e) {
            throw new MemberException(MemberErrorCode.INVALID_SOCIAL_INFO, e);
        }
    }

    private void validateDuplicatedNickname(String nickname) {
        if (userAccountUseCase.existsByNickname(nickname)) {
            throw new MemberException(MemberErrorCode.DUPLICATED_NICKNAME);
        }
    }

    private void validateNicknameChangeAllowed(UserView member, Instant now) {
        // 마지막 변경 시각부터 30일이 지나야 다음 닉네임 변경을 허용한다.
        Instant nextChangeAt = member.nicknameChangedAt().plus(NICKNAME_CHANGE_INTERVAL);
        if (now.isBefore(nextChangeAt)) {
            throw new MemberException(MemberErrorCode.NICKNAME_CHANGE_RESTRICTED);
        }
    }

    private UserView findMember(UUID userId) {
        UserView user = userAccountUseCase.findById(userId)
                .orElseThrow(() -> new MemberException(MemberErrorCode.USER_NOT_FOUND));

        if (user.role() != UserRole.USER) {
            throw new MemberException(MemberErrorCode.USER_NOT_FOUND);
        }

        return user;
    }

    private UserView findRefreshTokenMember(UUID userId) {
        UserView user = userAccountUseCase.findById(userId)
                .orElseThrow(() -> new MemberException(MemberErrorCode.INVALID_REFRESH_TOKEN));

        if (user.role() != UserRole.USER) {
            throw new MemberException(MemberErrorCode.INVALID_REFRESH_TOKEN);
        }

        return user;
    }

    private JwtTokenClaims parseRefreshToken(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new MemberException(MemberErrorCode.INVALID_REFRESH_TOKEN);
        }

        JwtTokenClaims claims = parseToken(refreshToken, MemberErrorCode.INVALID_REFRESH_TOKEN);

        if (claims.tokenType() != JwtTokenType.REFRESH) {
            throw new MemberException(MemberErrorCode.INVALID_REFRESH_TOKEN);
        }

        return claims;
    }

    private JwtTokenClaims parseAccessToken(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new MemberException(MemberErrorCode.INVALID_ACCESS_TOKEN);
        }

        JwtTokenClaims claims = parseToken(accessToken, MemberErrorCode.INVALID_ACCESS_TOKEN);

        if (claims.tokenType() != JwtTokenType.ACCESS) {
            throw new MemberException(MemberErrorCode.INVALID_ACCESS_TOKEN);
        }

        return claims;
    }

    private JwtTokenClaims parseToken(String token, MemberErrorCode errorCode) {
        try {
            return jwtTokenProvider.parseToken(token);
        } catch (RuntimeException e) {
            throw new MemberException(errorCode, e);
        }
    }

    private void validateAccessTokenOwner(JwtTokenClaims claims, UUID userId) {
        if (!claims.userId().equals(userId)) {
            throw new MemberException(MemberErrorCode.INVALID_ACCESS_TOKEN);
        }
    }

    private void blacklistAccessToken(JwtTokenClaims claims) {
        Duration remainingExpiration = getRemainingExpiration(claims);

        if (remainingExpiration.isZero() || remainingExpiration.isNegative()) {
            return;
        }

        accessTokenBlacklist.save(claims.tokenId(), remainingExpiration);
    }

    private Duration getRemainingExpiration(JwtTokenClaims claims) {
        Duration remainingExpiration = Duration.between(timeProvider.now(), claims.expiresAt());

        if (remainingExpiration.isNegative()) {
            return Duration.ZERO;
        }

        return remainingExpiration;
    }

    private JwtSubject toJwtSubject(UserView member, UUID sessionId) {
        return new JwtSubject(member.userId(), sessionId, member.role().name(), member.nickname(), member.email());
    }

    private MemberMeResult toMemberMeResult(UserView member) {
        return new MemberMeResult(
                member.userId(),
                member.nickname(),
                member.name(),
                member.birthDate(),
                member.gender() == null ? null : member.gender().name(),
                member.phoneNumber(),
                member.profileImageKey(),
                member.email(),
                member.role().name(),
                member.status().name(),
                member.grade().name(),
                member.pointBalance(),
                member.marketingAgreed(),
                member.provider() == null ? null : member.provider().name()
        );
    }

    private SocialSignupInfoResult toSocialSignupInfoResult(SocialSignupInfo socialSignupInfo) {
        return new SocialSignupInfoResult(
                socialSignupInfo.phoneNumber(),
                socialSignupInfo.name(),
                socialSignupInfo.providerEmail(),
                socialSignupInfo.nickname(),
                socialSignupInfo.profileImageUrl(),
                socialSignupInfo.gender() == null ? null : socialSignupInfo.gender().name(),
                socialSignupInfo.birthDate()
        );
    }
}
