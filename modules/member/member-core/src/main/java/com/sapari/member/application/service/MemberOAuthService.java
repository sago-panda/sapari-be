package com.sapari.member.application.service;

import lombok.RequiredArgsConstructor;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import com.sapari.common.web.security.jwt.JwtSubject;
import com.sapari.common.web.security.jwt.JwtTokenProvider;
import com.sapari.member.application.dto.SocialSignupInfo;
import com.sapari.member.command.MemberOAuthCommand;
import com.sapari.member.domain.exception.MemberErrorCode;
import com.sapari.member.domain.exception.MemberException;
import com.sapari.member.infrastructure.redis.SocialLoginCodeRedisRepository;
import com.sapari.member.infrastructure.redis.SocialSignupRedisRepository;
import com.sapari.member.port.MemberOAuthUseCase;
import com.sapari.member.result.MemberOAuthResult;
import com.sapari.member.result.SocialLoginTokenResult;
import com.sapari.common.web.security.RefreshTokenStore;
import com.sapari.user.model.ProviderType;
import com.sapari.user.model.UserRole;
import com.sapari.user.port.UserAccountUseCase;
import com.sapari.user.view.UserView;

@Service
@RequiredArgsConstructor
public class MemberOAuthService implements MemberOAuthUseCase {

    private final UserAccountUseCase userAccountUseCase;
    private final SocialSignupRedisRepository socialSignupRedisRepository;
    private final SocialLoginCodeRedisRepository socialLoginCodeRedisRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenStore refreshTokenStore;
    private final ObjectMapper objectMapper;

    /**
     * OAuth 인증 사용자가 기존 회원이면 임시 로그인 code를, 신규 회원이면 회원가입 sid를 발급
     */
    @Override
    @Transactional(readOnly = true)
    public MemberOAuthResult handleOAuthSuccess(MemberOAuthCommand command) {
        ProviderType provider = toProviderType(command.provider());
        validateProviderId(command.providerId());

        return userAccountUseCase.findBySocialAccount(provider, command.providerId())
                .map(this::createLoginSuccessResult)
                .orElseGet(() -> createSignupRequiredResult(command));
    }

    /**
     * 기존 회원에게 전달할 임시 로그인 code를 만들고 Redis에 token 정보를 짧게 저장
     */
    private MemberOAuthResult createLoginSuccessResult(UserView user) {
        if (user.role() != UserRole.USER) {
            throw new MemberException(MemberErrorCode.USER_NOT_FOUND);
        }

        String accessToken = jwtTokenProvider.createAccessToken(toJwtSubject(user));
        String refreshToken = jwtTokenProvider.createRefreshToken(toJwtSubject(user));
        String loginCode = UUID.randomUUID().toString();
        String socialLoginTokenInfoJson = toJson(new SocialLoginTokenResult(
                user.userId(),
                accessToken,
                refreshToken
        ));

        refreshTokenStore.save(user.userId(), refreshToken);
        socialLoginCodeRedisRepository.save(loginCode, socialLoginTokenInfoJson);

        return MemberOAuthResult.loginSuccess(loginCode);
    }

    /**
     * 신규 회원 추가정보 입력을 위해 소셜 정보를 Redis에 임시 저장하고 signup sid를 발급
     */
    private MemberOAuthResult createSignupRequiredResult(MemberOAuthCommand command) {
        String signupSid = UUID.randomUUID().toString();
        String socialSignupInfoJson = toJson(SocialSignupInfo.from(command));

        socialSignupRedisRepository.save(signupSid, socialSignupInfoJson);

        return MemberOAuthResult.signupRequired(signupSid);
    }

    private ProviderType toProviderType(String provider) {
        if (provider == null || provider.isBlank()) {
            throw new MemberException(MemberErrorCode.INVALID_OAUTH_PROVIDER);
        }

        try {
            return ProviderType.valueOf(provider);
        } catch (IllegalArgumentException e) {
            throw new MemberException(MemberErrorCode.INVALID_OAUTH_PROVIDER, e);
        }
    }

    private void validateProviderId(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            throw new MemberException(MemberErrorCode.INVALID_SOCIAL_INFO);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new MemberException(MemberErrorCode.INVALID_SOCIAL_INFO, e);
        }
    }

    private JwtSubject toJwtSubject(UserView member) {
        return new JwtSubject(member.userId(), member.role().name(), member.nickname(), member.email());
    }
}
