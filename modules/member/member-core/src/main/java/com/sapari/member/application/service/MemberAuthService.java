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
import com.sapari.member.result.MemberTokenReissueResult;
import com.sapari.member.result.SocialSignupInfoResult;
import com.sapari.member.result.SocialLoginTokenResult;
import com.sapari.member.result.SocialSignupResult;
import com.sapari.user.domain.model.User;
import com.sapari.user.domain.model.UserGender;
import com.sapari.user.domain.model.UserRole;
import com.sapari.user.domain.repository.UserRepository;
import com.sapari.user.infrastructure.security.redis.AccessTokenBlacklistRedisRepository;
import com.sapari.user.infrastructure.security.redis.RefreshTokenRedisRepository;

@Service
@RequiredArgsConstructor
public class MemberAuthService implements MemberAuthUseCase {

    private static final Duration NICKNAME_CHANGE_INTERVAL = Duration.ofDays(30);

    private final SocialSignupRedisRepository socialSignupRedisRepository;
    private final SocialLoginCodeRedisRepository socialLoginCodeRedisRepository;
    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenRedisRepository refreshTokenRedisRepository;
    private final AccessTokenBlacklistRedisRepository accessTokenBlacklistRedisRepository;
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
            User savedUser = userRepository.save(createMember(command, socialSignupInfo));
            socialSignupRedisRepository.delete(signupSid);

            String accessToken = jwtTokenProvider.createAccessToken(toJwtSubject(savedUser));
            String refreshToken = jwtTokenProvider.createRefreshToken(toJwtSubject(savedUser));
            refreshTokenRedisRepository.save(savedUser.userId(), refreshToken);

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

        User member = findRefreshTokenMember(claims.userId());
        String savedRefreshToken = refreshTokenRedisRepository.findByUserId(member.userId())
                .orElseThrow(() -> new MemberException(MemberErrorCode.INVALID_REFRESH_TOKEN));

        if (!savedRefreshToken.equals(refreshToken)) {
            throw new MemberException(MemberErrorCode.INVALID_REFRESH_TOKEN);
        }

        String accessToken = jwtTokenProvider.createAccessToken(toJwtSubject(member));

        return new MemberTokenReissueResult(member.userId(), accessToken);
    }

    @Override
    @Transactional
    public void logout(MemberLogoutCommand command) {
        refreshTokenRedisRepository.delete(command.userId());
        Duration remainingExpiration = getRemainingExpiration(command.accessToken());

        if (remainingExpiration.isZero() || remainingExpiration.isNegative()) {
            return;
        }

        accessTokenBlacklistRedisRepository.save(command.accessToken(), remainingExpiration);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isPhoneNumberDuplicated(String phoneNumber) {
        return userRepository.existsByPhoneNumber(phoneNumber);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isEmailDuplicated(String email) {
        return userRepository.existsByEmail(email);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isNicknameDuplicated(String nickname) {
        return userRepository.existsByNickname(nickname);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isMyNicknameDuplicated(UUID userId, String nickname) {
        findMember(userId);

        return userRepository.existsByNicknameAndUserIdNot(nickname, userId);
    }

    @Override
    @Transactional(readOnly = true)
    public MemberMeResult getMyInfo(UUID userId) {
        return toMemberMeResult(findMember(userId));
    }

    @Override
    @Transactional
    public MemberMeResult updateNickname(MemberNicknameUpdateCommand command) {
        User member = findMember(command.userId());

        Instant now = timeProvider.now();
        validateNicknameChangeAllowed(member, now);
        validateDuplicatedNickname(command.userId(), command.nickname());

        User updatedMember = member.updateNickname(command.nickname(), now);

        try {
            return toMemberMeResult(userRepository.save(updatedMember));
        } catch (DataIntegrityViolationException e) {
            throw new MemberException(MemberErrorCode.DUPLICATED_NICKNAME, e);
        }
    }

    private User createMember(SocialSignupCommand command, SocialSignupInfo socialSignupInfo) {
        Instant now = timeProvider.now();

        return User.createSocialMember(
                command.nickname(),
                command.name(),
                command.birthDate(),
                UserGender.valueOf(command.gender()),
                command.phoneNumber(),
                command.email(),
                command.isMarketingAgreed(),
                socialSignupInfo.provider(),
                socialSignupInfo.providerId(),
                socialSignupInfo.providerEmail(),
                now,
                now
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

    private void validateDuplicatedNickname(UUID userId, String nickname) {
        if (userRepository.existsByNicknameAndUserIdNot(nickname, userId)) {
            throw new MemberException(MemberErrorCode.DUPLICATED_NICKNAME);
        }
    }

    private void validateNicknameChangeAllowed(User member, Instant now) {
        // 마지막 변경 시각부터 30일이 지나야 다음 닉네임 변경을 허용한다.
        Instant nextChangeAt = member.nicknameChangedAt().plus(NICKNAME_CHANGE_INTERVAL);
        if (now.isBefore(nextChangeAt)) {
            throw new MemberException(MemberErrorCode.NICKNAME_CHANGE_RESTRICTED);
        }
    }

    private User findMember(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new MemberException(MemberErrorCode.USER_NOT_FOUND));

        if (user.role() != UserRole.USER) {
            throw new MemberException(MemberErrorCode.USER_NOT_FOUND);
        }

        return user;
    }

    private User findRefreshTokenMember(UUID userId) {
        User user = userRepository.findById(userId)
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

        JwtTokenClaims claims = parseToken(refreshToken);

        if (claims.tokenType() != JwtTokenType.REFRESH) {
            throw new MemberException(MemberErrorCode.INVALID_REFRESH_TOKEN);
        }

        return claims;
    }

    private JwtTokenClaims parseToken(String token) {
        try {
            return jwtTokenProvider.parseToken(token);
        } catch (RuntimeException e) {
            throw new MemberException(MemberErrorCode.INVALID_REFRESH_TOKEN, e);
        }
    }

    private Duration getRemainingExpiration(String accessToken) {
        try {
            JwtTokenClaims claims = jwtTokenProvider.parseToken(accessToken);
            Duration remainingExpiration = Duration.between(timeProvider.now(), claims.expiresAt());

            if (remainingExpiration.isNegative()) {
                return Duration.ZERO;
            }

            return remainingExpiration;
        } catch (RuntimeException e) {
            throw new MemberException(MemberErrorCode.INVALID_ACCESS_TOKEN, e);
        }
    }

    private JwtSubject toJwtSubject(User member) {
        return new JwtSubject(member.userId(), member.role().name());
    }

    private MemberMeResult toMemberMeResult(User member) {
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
