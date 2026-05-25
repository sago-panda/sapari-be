package com.sapari.seller.application.service;

import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
import com.sapari.seller.port.SellerAuthFacade;
import com.sapari.seller.result.SellerLoginResult;
import com.sapari.seller.result.SellerMeResult;
import com.sapari.seller.result.SellerSignupResult;
import com.sapari.seller.result.SellerTokenReissueResult;
import com.sapari.user.domain.model.User;
import com.sapari.user.domain.model.UserRole;
import com.sapari.user.domain.repository.UserRepository;
import com.sapari.user.infrastructure.security.redis.AccessTokenBlacklistRedisRepository;
import com.sapari.user.infrastructure.security.redis.RefreshTokenRedisRepository;

@Service
@RequiredArgsConstructor
public class SellerAuthService implements SellerAuthFacade {

    private final UserRepository userRepository;
    private final LocalCredentialRepository localCredentialRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenRedisRepository refreshTokenRedisRepository;
    private final AccessTokenBlacklistRedisRepository accessTokenBlacklistRedisRepository;

    @Override
    @Transactional
    public SellerSignupResult signup(SellerSignupCommand command) {
        try {
            User savedUser = userRepository.save(createSeller(command));
            LocalCredential localCredential = LocalCredential.create(
                    savedUser.userId(),
                    passwordEncoder.encode(command.password())
            );

            localCredentialRepository.save(localCredential);

            return new SellerSignupResult(savedUser.userId());
        } catch (DataIntegrityViolationException e) {
            throw new SellerException(SellerErrorCode.DUPLICATED_SIGNUP_INFO, e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isEmailDuplicated(String email) {
        return userRepository.existsByEmail(email);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isPhoneNumberDuplicated(String phoneNumber) {
        return userRepository.existsByPhoneNumber(phoneNumber);
    }

    @Override
    @Transactional
    public SellerLoginResult login(SellerLoginCommand command) {
        User seller = findSellerByEmail(command.email());
        LocalCredential localCredential = findLocalCredential(seller.userId());

        if (!passwordEncoder.matches(command.password(), localCredential.passwordHash())) {
            throw new SellerException(SellerErrorCode.INVALID_LOGIN_CREDENTIALS);
        }

        String accessToken = jwtTokenProvider.createAccessToken(toJwtSubject(seller));
        String refreshToken = jwtTokenProvider.createRefreshToken(toJwtSubject(seller));
        refreshTokenRedisRepository.save(seller.userId(), refreshToken);

        return new SellerLoginResult(seller.userId(), accessToken, refreshToken);
    }

    @Override
    @Transactional(readOnly = true)
    public SellerTokenReissueResult reissueAccessToken(String refreshToken) {
        validateRefreshToken(refreshToken);

        User seller = findRefreshTokenSeller(refreshToken);
        String savedRefreshToken = refreshTokenRedisRepository.findByUserId(seller.userId())
                .orElseThrow(() -> new SellerException(SellerErrorCode.INVALID_REFRESH_TOKEN));

        if (!savedRefreshToken.equals(refreshToken)) {
            throw new SellerException(SellerErrorCode.INVALID_REFRESH_TOKEN);
        }

        String accessToken = jwtTokenProvider.createAccessToken(toJwtSubject(seller));

        return new SellerTokenReissueResult(seller.userId(), accessToken);
    }

    @Override
    @Transactional
    public void logout(SellerLogoutCommand command) {
        refreshTokenRedisRepository.delete(command.userId());
        Duration remainingExpiration = getRemainingExpiration(command.accessToken());

        if (remainingExpiration.isZero() || remainingExpiration.isNegative()) {
            return;
        }

        accessTokenBlacklistRedisRepository.save(command.accessToken(), remainingExpiration);
    }

    @Override
    @Transactional(readOnly = true)
    public SellerMeResult getMyInfo(UUID userId) {
        return toSellerMeResult(findSeller(userId));
    }

    @Override
    @Transactional
    public SellerMeResult updateMyInfo(SellerMeUpdateCommand command) {
        User seller = findSeller(command.userId());
        User updatedSeller = seller.updateProfile(
                command.nickname(),
                command.name(),
                command.birthDate(),
                command.phoneNumber(),
                command.profileImageKey(),
                command.email(),
                command.marketingAgreed()
        );

        return toSellerMeResult(userRepository.save(updatedSeller));
    }

    private User createSeller(SellerSignupCommand command) {
        return User.createSeller(
                command.nickname(),
                command.name(),
                command.birthDate(),
                command.phoneNumber(),
                command.email(),
                command.marketingAgreed()
        );
    }

    private User findSellerByEmail(String email) {
        return userRepository.findByEmailAndRole(email, UserRole.SELLER)
                .orElseThrow(() -> new SellerException(SellerErrorCode.INVALID_LOGIN_CREDENTIALS));
    }

    private LocalCredential findLocalCredential(UUID userId) {
        return localCredentialRepository.findById(userId)
                .orElseThrow(() -> new SellerException(SellerErrorCode.INVALID_LOGIN_CREDENTIALS));
    }

    private User findRefreshTokenSeller(String refreshToken) {
        UUID userId = getRefreshTokenUserId(refreshToken);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new SellerException(SellerErrorCode.INVALID_REFRESH_TOKEN));

        if (!user.isSeller()) {
            throw new SellerException(SellerErrorCode.INVALID_REFRESH_TOKEN);
        }

        return user;
    }

    private User findSeller(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new SellerException(SellerErrorCode.USER_NOT_FOUND));

        if (!user.isSeller()) {
            throw new SellerException(SellerErrorCode.USER_NOT_FOUND);
        }

        return user;
    }

    private void validateRefreshToken(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new SellerException(SellerErrorCode.INVALID_REFRESH_TOKEN);
        }

        if (!jwtTokenProvider.validateToken(refreshToken)) {
            throw new SellerException(SellerErrorCode.INVALID_REFRESH_TOKEN);
        }

        if (getRefreshTokenType(refreshToken) != JwtTokenType.REFRESH) {
            throw new SellerException(SellerErrorCode.INVALID_REFRESH_TOKEN);
        }
    }

    private UUID getRefreshTokenUserId(String refreshToken) {
        try {
            return jwtTokenProvider.getUserId(refreshToken);
        } catch (RuntimeException e) {
            throw new SellerException(SellerErrorCode.INVALID_REFRESH_TOKEN, e);
        }
    }

    private JwtTokenType getRefreshTokenType(String refreshToken) {
        try {
            return jwtTokenProvider.getTokenType(refreshToken);
        } catch (RuntimeException e) {
            throw new SellerException(SellerErrorCode.INVALID_REFRESH_TOKEN, e);
        }
    }

    private Duration getRemainingExpiration(String accessToken) {
        try {
            return jwtTokenProvider.getRemainingExpiration(accessToken);
        } catch (RuntimeException e) {
            throw new SellerException(SellerErrorCode.INVALID_ACCESS_TOKEN, e);
        }
    }

    private JwtSubject toJwtSubject(User seller) {
        return new JwtSubject(seller.userId(), seller.role().name());
    }

    private SellerMeResult toSellerMeResult(User seller) {
        return new SellerMeResult(
                seller.userId(),
                seller.nickname(),
                seller.name(),
                seller.birthDate(),
                seller.phoneNumber(),
                seller.profileImageKey(),
                seller.email(),
                seller.role().name(),
                seller.status().name(),
                seller.grade().name(),
                seller.pointBalance(),
                seller.marketingAgreed()
        );
    }
}
