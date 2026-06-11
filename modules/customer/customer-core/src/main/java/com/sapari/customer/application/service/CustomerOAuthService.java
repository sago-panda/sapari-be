package com.sapari.customer.application.service;

import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import com.sapari.common.securityjwt.jwt.JwtSubject;
import com.sapari.common.securityjwt.jwt.JwtTokenClaims;
import com.sapari.common.securityjwt.jwt.JwtTokenProvider;
import com.sapari.global.time.TimeProvider;
import com.sapari.customer.application.dto.SocialSignupInfo;
import com.sapari.customer.command.CustomerOAuthCommand;
import com.sapari.customer.domain.exception.CustomerErrorCode;
import com.sapari.customer.domain.exception.CustomerException;
import com.sapari.customer.domain.repository.SocialLoginCodeRepository;
import com.sapari.customer.domain.repository.SocialSignupRepository;
import com.sapari.customer.port.CustomerOAuthUseCase;
import com.sapari.customer.result.CustomerOAuthResult;
import com.sapari.customer.result.SocialLoginTokenResult;
import com.sapari.common.securityjwt.store.RefreshTokenStore;
import com.sapari.user.model.ProviderType;
import com.sapari.user.model.UserRole;
import com.sapari.user.port.UserAccountUseCase;
import com.sapari.user.view.UserView;

@Service
@RequiredArgsConstructor
public class CustomerOAuthService implements CustomerOAuthUseCase {

    private final UserAccountUseCase userAccountUseCase;
    private final SocialSignupRepository socialSignupRedisRepository;
    private final SocialLoginCodeRepository socialLoginCodeRedisRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenStore refreshTokenStore;
    private final TimeProvider timeProvider;
    private final ObjectMapper objectMapper;

    /**
     * OAuth 인증 사용자가 기존 고객이면 임시 로그인 code를, 신규 고객이면 가입 sid를 발급
     */
    @Override
    @Transactional
    public CustomerOAuthResult handleOAuthSuccess(CustomerOAuthCommand command) {
        ProviderType provider = toProviderType(command.provider());
        validateProviderId(command.providerId());

        return userAccountUseCase.findBySocialAccount(provider, command.providerId())
                .map(this::createLoginSuccessResult)
                .orElseGet(() -> createSignupRequiredResult(command));
    }

    /**
     * 기존 고객에게 전달할 임시 로그인 code를 만들고 Redis에 token 정보를 짧게 저장
     */
    private CustomerOAuthResult createLoginSuccessResult(UserView user) {
        if (user.role() != UserRole.USER) {
            throw new CustomerException(CustomerErrorCode.USER_NOT_FOUND);
        }

        UUID sessionId = UUID.randomUUID();
        JwtSubject subject = toJwtSubject(user, sessionId);
        String accessToken = jwtTokenProvider.createAccessToken(subject);
        String refreshToken = issueRefreshToken(subject);
        String loginCode = UUID.randomUUID().toString();
        String socialLoginTokenInfoJson = toJson(new SocialLoginTokenResult(
                user.userId(),
                accessToken,
                refreshToken
        ));

        socialLoginCodeRedisRepository.save(loginCode, socialLoginTokenInfoJson);

        return CustomerOAuthResult.loginSuccess(loginCode);
    }

    /**
     * 신규 고객 추가정보 입력을 위해 소셜 정보를 Redis에 임시 저장하고 signup sid를 발급
     */
    private CustomerOAuthResult createSignupRequiredResult(CustomerOAuthCommand command) {
        String signupSid = UUID.randomUUID().toString();
        String socialSignupInfoJson = toJson(SocialSignupInfo.from(command));

        socialSignupRedisRepository.save(signupSid, socialSignupInfoJson);

        return CustomerOAuthResult.signupRequired(signupSid);
    }

    private ProviderType toProviderType(String provider) {
        if (provider == null || provider.isBlank()) {
            throw new CustomerException(CustomerErrorCode.INVALID_OAUTH_PROVIDER);
        }

        try {
            return ProviderType.valueOf(provider);
        } catch (IllegalArgumentException e) {
            throw new CustomerException(CustomerErrorCode.INVALID_OAUTH_PROVIDER, e);
        }
    }

    private void validateProviderId(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            throw new CustomerException(CustomerErrorCode.INVALID_SOCIAL_INFO);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new CustomerException(CustomerErrorCode.INVALID_SOCIAL_INFO, e);
        }
    }

    /**
     * 로그인 세션의 Refresh Token을 발급하고 현재 Refresh Token ID를 저장한다.
     */
    private String issueRefreshToken(JwtSubject subject) {
        String refreshToken = jwtTokenProvider.createRefreshToken(subject);
        JwtTokenClaims refreshClaims = jwtTokenProvider.parseToken(refreshToken);

        refreshTokenStore.save(
                refreshClaims.sessionId(),
                refreshClaims.tokenId(),
                getRemainingExpiration(refreshClaims)
        );

        return refreshToken;
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
}
