package com.sapari.customer.application.service;

import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import com.sapari.common.securityjwt.jwt.JwtSubject;
import com.sapari.common.securityjwt.jwt.JwtTokenClaims;
import com.sapari.common.securityjwt.jwt.JwtTokenProvider;
import com.sapari.common.securityjwt.jwt.JwtTokenType;
import com.sapari.global.time.TimeProvider;
import com.sapari.customer.application.dto.SocialSignupInfo;
import com.sapari.customer.command.CustomerLogoutCommand;
import com.sapari.customer.command.CustomerNicknameUpdateCommand;
import com.sapari.customer.command.SocialSignupCommand;
import com.sapari.customer.domain.exception.CustomerErrorCode;
import com.sapari.customer.domain.exception.CustomerException;
import com.sapari.customer.domain.repository.SocialLoginCodeRepository;
import com.sapari.customer.domain.repository.SocialSignupRepository;
import com.sapari.customer.port.CustomerAuthUseCase;
import com.sapari.customer.result.CustomerMeResult;
import com.sapari.customer.result.CustomerNicknameUpdateResult;
import com.sapari.customer.result.CustomerTokenReissueResult;
import com.sapari.customer.result.SocialSignupInfoResult;
import com.sapari.customer.result.SocialLoginTokenResult;
import com.sapari.customer.result.SocialSignupResult;
import com.sapari.user.command.RegisterSocialCustomerCommand;
import com.sapari.common.securityjwt.store.AccessTokenBlacklist;
import com.sapari.common.securityjwt.store.RefreshTokenStore;
import com.sapari.common.securityjwt.store.SessionRevocationStore;
import com.sapari.user.model.UserGender;
import com.sapari.user.model.UserRole;
import com.sapari.user.port.UserAccountUseCase;
import com.sapari.user.view.UserView;

@Service
@RequiredArgsConstructor
public class CustomerAuthService implements CustomerAuthUseCase {

    private static final Duration NICKNAME_CHANGE_INTERVAL = Duration.ofDays(30);

    private final SocialSignupRepository socialSignupRedisRepository;
    private final SocialLoginCodeRepository socialLoginCodeRedisRepository;
    private final UserAccountUseCase userAccountUseCase;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenStore refreshTokenStore;
    private final SessionRevocationStore sessionRevocationStore;
    private final AccessTokenBlacklist accessTokenBlacklist;
    private final TimeProvider timeProvider;
    private final ObjectMapper objectMapper;

    /**
     * signup sid로 임시 소셜 정보를 조회하고 추가정보를 합쳐 구매자 가입
     */
    @Override
    @Transactional
    public SocialSignupResult completeSocialSignup(String signupSid, SocialSignupCommand command) {
        SocialSignupInfo socialSignupInfo = findSocialSignupInfo(signupSid);

        try {
            UserView savedUser = userAccountUseCase.registerSocialCustomer(toRegisterCommand(command, socialSignupInfo));
            socialSignupRedisRepository.delete(signupSid);

            UUID sessionId = UUID.randomUUID();
            JwtSubject subject = toJwtSubject(savedUser, sessionId);
            String accessToken = jwtTokenProvider.createAccessToken(subject);
            String refreshToken = issueRefreshToken(subject);

            return new SocialSignupResult(savedUser.userId(), accessToken, refreshToken);
        } catch (DataIntegrityViolationException e) {
            throw new CustomerException(CustomerErrorCode.DUPLICATED_SIGNUP_INFO, e);
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
            throw new CustomerException(CustomerErrorCode.INVALID_LOGIN_CODE);
        }

        SocialLoginTokenResult tokenResult = socialLoginCodeRedisRepository.findByCode(temporaryLoginCode)
                .map(this::readSocialLoginTokenResult)
                .orElseThrow(() -> new CustomerException(CustomerErrorCode.INVALID_LOGIN_CODE));

        socialLoginCodeRedisRepository.delete(temporaryLoginCode);

        return tokenResult;
    }

    /**
     * Refresh Token을 검증해 새 Access Token을 발급
     */
    @Override
    @Transactional
    public CustomerTokenReissueResult reissueAccessToken(String refreshToken) {
        JwtTokenClaims claims = parseRefreshToken(refreshToken);

        UserView customer = findRefreshTokenCustomer(claims.userId());
        JwtSubject subject = toJwtSubject(customer, claims.sessionId());
        String accessToken = jwtTokenProvider.createAccessToken(subject);
        RotatedRefreshToken rotatedRefreshToken = rotateRefreshToken(subject, claims);

        return new CustomerTokenReissueResult(
                customer.userId(),
                accessToken,
                rotatedRefreshToken.token(),
                rotatedRefreshToken.maxAgeSeconds()
        );
    }

    @Override
    @Transactional
    public void logout(CustomerLogoutCommand command) {
        JwtTokenClaims claims = parseAccessToken(command.accessToken());
        validateAccessTokenOwner(claims, command.userId());

        // 로그아웃은 현재 sid 세션 전체를 폐기해 같은 세션의 Access Token까지 차단한다.
        refreshTokenStore.deleteBySessionId(claims.sessionId());
        sessionRevocationStore.revoke(claims.sessionId());
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
    public CustomerMeResult getMyInfo(UUID userId) {
        return toCustomerMeResult(findCustomer(userId));
    }

    @Override
    @Transactional
    public CustomerNicknameUpdateResult updateNickname(CustomerNicknameUpdateCommand command) {
        JwtTokenClaims accessClaims = parseAccessToken(command.accessToken());
        validateAccessTokenOwner(accessClaims, command.userId());

        UserView customer = findCustomer(command.userId());

        validateDuplicatedNickname(command.nickname());

        Instant now = timeProvider.now();
        validateNicknameChangeAllowed(customer, now);

        try {
            UserView savedCustomer = userAccountUseCase.changeNickname(command.userId(), command.nickname());
            // nickname이 바뀌었으므로 기존 access jti를 폐기하고 같은 sid로 새 Access Token을 발급
            blacklistAccessToken(accessClaims);
            String accessToken = jwtTokenProvider.createAccessToken(toJwtSubject(savedCustomer, accessClaims.sessionId()));

            return new CustomerNicknameUpdateResult(toCustomerMeResult(savedCustomer), accessToken);
        } catch (DataIntegrityViolationException e) {
            throw new CustomerException(CustomerErrorCode.DUPLICATED_NICKNAME, e);
        }
    }

    private RegisterSocialCustomerCommand toRegisterCommand(SocialSignupCommand command, SocialSignupInfo socialSignupInfo) {
        return new RegisterSocialCustomerCommand(
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
            throw new CustomerException(CustomerErrorCode.INVALID_SIGNUP_SESSION);
        }

        return socialSignupRedisRepository.findBySid(signupSid)
                .map(this::readSocialSignupInfo)
                .orElseThrow(() -> new CustomerException(CustomerErrorCode.INVALID_SIGNUP_SESSION));
    }

    private SocialSignupInfo readSocialSignupInfo(String json) {
        try {
            return objectMapper.readValue(json, SocialSignupInfo.class);
        } catch (Exception e) {
            throw new CustomerException(CustomerErrorCode.INVALID_SOCIAL_INFO, e);
        }
    }

    private SocialLoginTokenResult readSocialLoginTokenResult(String json) {
        try {
            return objectMapper.readValue(json, SocialLoginTokenResult.class);
        } catch (Exception e) {
            throw new CustomerException(CustomerErrorCode.INVALID_SOCIAL_INFO, e);
        }
    }

    private void validateDuplicatedNickname(String nickname) {
        if (userAccountUseCase.existsByNickname(nickname)) {
            throw new CustomerException(CustomerErrorCode.DUPLICATED_NICKNAME);
        }
    }

    private void validateNicknameChangeAllowed(UserView customer, Instant now) {
        // 마지막 변경 시각부터 30일이 지나야 다음 닉네임 변경을 허용한다.
        Instant nextChangeAt = customer.nicknameChangedAt().plus(NICKNAME_CHANGE_INTERVAL);
        if (now.isBefore(nextChangeAt)) {
            throw new CustomerException(CustomerErrorCode.NICKNAME_CHANGE_RESTRICTED);
        }
    }

    private UserView findCustomer(UUID userId) {
        UserView user = userAccountUseCase.findById(userId)
                .orElseThrow(() -> new CustomerException(CustomerErrorCode.USER_NOT_FOUND));

        if (user.role() != UserRole.USER) {
            throw new CustomerException(CustomerErrorCode.USER_NOT_FOUND);
        }

        return user;
    }

    private UserView findRefreshTokenCustomer(UUID userId) {
        UserView user = userAccountUseCase.findById(userId)
                .orElseThrow(() -> new CustomerException(CustomerErrorCode.INVALID_REFRESH_TOKEN));

        if (user.role() != UserRole.USER) {
            throw new CustomerException(CustomerErrorCode.INVALID_REFRESH_TOKEN);
        }

        return user;
    }

    private JwtTokenClaims parseRefreshToken(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new CustomerException(CustomerErrorCode.INVALID_REFRESH_TOKEN);
        }

        JwtTokenClaims claims = parseToken(refreshToken, CustomerErrorCode.INVALID_REFRESH_TOKEN);

        if (claims.tokenType() != JwtTokenType.REFRESH) {
            throw new CustomerException(CustomerErrorCode.INVALID_REFRESH_TOKEN);
        }

        return claims;
    }

    private JwtTokenClaims parseAccessToken(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new CustomerException(CustomerErrorCode.INVALID_ACCESS_TOKEN);
        }

        JwtTokenClaims claims = parseToken(accessToken, CustomerErrorCode.INVALID_ACCESS_TOKEN);

        if (claims.tokenType() != JwtTokenType.ACCESS) {
            throw new CustomerException(CustomerErrorCode.INVALID_ACCESS_TOKEN);
        }

        return claims;
    }

    private JwtTokenClaims parseToken(String token, CustomerErrorCode errorCode) {
        try {
            return jwtTokenProvider.parseToken(token);
        } catch (RuntimeException e) {
            throw new CustomerException(errorCode, e);
        }
    }

    /**
     * 로그인 세션의 Refresh Token을 발급하고 현재 Refresh Token ID를 저장한다.
     */
    private String issueRefreshToken(JwtSubject subject) {
        String refreshToken = jwtTokenProvider.createRefreshToken(subject);
        JwtTokenClaims refreshClaims = parseRefreshToken(refreshToken);

        refreshTokenStore.save(
                refreshClaims.sessionId(),
                refreshClaims.tokenId(),
                getRemainingExpiration(refreshClaims)
        );

        return refreshToken;
    }

    /**
     * 같은 로그인 세션에서 현재 Refresh Token ID를 새 Refresh Token ID로 교체한다.
     */
    private RotatedRefreshToken rotateRefreshToken(JwtSubject subject, JwtTokenClaims previousRefreshClaims) {
        String refreshToken = jwtTokenProvider.createRefreshTokenForRotation(subject, previousRefreshClaims.expiresAt());
        JwtTokenClaims refreshClaims = parseRefreshToken(refreshToken);
        Duration refreshTokenTtl = getRemainingExpiration(refreshClaims);

        if (refreshTokenTtl.toMillis() < 1) {
            throw new CustomerException(CustomerErrorCode.INVALID_REFRESH_TOKEN);
        }

        boolean rotated = refreshTokenStore.rotate(
                previousRefreshClaims.sessionId(),
                previousRefreshClaims.tokenId(),
                refreshClaims.tokenId(),
                refreshTokenTtl
        );

        if (!rotated) {
            refreshTokenStore.deleteBySessionId(previousRefreshClaims.sessionId());
            sessionRevocationStore.revoke(previousRefreshClaims.sessionId());
            throw new CustomerException(CustomerErrorCode.INVALID_REFRESH_TOKEN);
        }

        return new RotatedRefreshToken(refreshToken, refreshTokenTtl.toSeconds());
    }

    private void validateAccessTokenOwner(JwtTokenClaims claims, UUID userId) {
        if (!claims.userId().equals(userId)) {
            throw new CustomerException(CustomerErrorCode.INVALID_ACCESS_TOKEN);
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

    private JwtSubject toJwtSubject(UserView customer, UUID sessionId) {
        return new JwtSubject(customer.userId(), sessionId, customer.role().name(), customer.nickname(), customer.email());
    }

    private CustomerMeResult toCustomerMeResult(UserView customer) {
        return new CustomerMeResult(
                customer.userId(),
                customer.nickname(),
                customer.name(),
                customer.birthDate(),
                customer.gender() == null ? null : customer.gender().name(),
                customer.phoneNumber(),
                customer.profileImageKey(),
                customer.email(),
                customer.role().name(),
                customer.status().name(),
                customer.grade().name(),
                customer.pointBalance(),
                customer.marketingAgreed(),
                customer.provider() == null ? null : customer.provider().name()
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

    private record RotatedRefreshToken(
            String token,
            long maxAgeSeconds
    ) {
    }
}
