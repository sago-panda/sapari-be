package com.sapari.seller.application.service;

import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sapari.common.web.security.jwt.JwtSubject;
import com.sapari.common.web.security.jwt.JwtTokenClaims;
import com.sapari.common.web.security.jwt.JwtTokenProvider;
import com.sapari.common.web.security.jwt.JwtTokenType;
import com.sapari.global.time.TimeProvider;
import com.sapari.seller.command.SellerLoginCommand;
import com.sapari.seller.command.SellerLogoutCommand;
import com.sapari.seller.command.SellerNicknameUpdateCommand;
import com.sapari.seller.command.SellerSignupCommand;
import com.sapari.seller.domain.exception.SellerErrorCode;
import com.sapari.seller.domain.exception.SellerException;
import com.sapari.seller.domain.model.LocalCredential;
import com.sapari.seller.domain.repository.LocalCredentialRepository;
import com.sapari.seller.port.SellerAuthUseCase;
import com.sapari.seller.result.SellerLoginResult;
import com.sapari.seller.result.SellerMeResult;
import com.sapari.seller.result.SellerNicknameUpdateResult;
import com.sapari.seller.result.SellerSignupResult;
import com.sapari.seller.result.SellerTokenReissueResult;
import com.sapari.user.command.RegisterSellerCommand;
import com.sapari.common.web.security.AccessTokenBlacklist;
import com.sapari.common.web.security.RefreshTokenStore;
import com.sapari.user.model.UserRole;
import com.sapari.user.port.UserAccountUseCase;
import com.sapari.user.view.UserView;

@Service
@RequiredArgsConstructor
public class SellerAuthService implements SellerAuthUseCase {

    private static final Duration NICKNAME_CHANGE_INTERVAL = Duration.ofDays(30);

    private final UserAccountUseCase userAccountUseCase;
    private final LocalCredentialRepository localCredentialRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenStore refreshTokenStore;
    private final AccessTokenBlacklist accessTokenBlacklist;
    private final TimeProvider timeProvider;

    @Override
    @Transactional
    public SellerSignupResult signup(SellerSignupCommand command) {
        try {
            UserView savedUser = userAccountUseCase.registerSeller(toRegisterCommand(command));
            LocalCredential localCredential = LocalCredential.create(
                    savedUser.userId(),
                    passwordEncoder.encode(command.password()),
                    timeProvider.now()
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
        return userAccountUseCase.existsByEmail(email);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isPhoneNumberDuplicated(String phoneNumber) {
        return userAccountUseCase.existsByPhoneNumber(phoneNumber);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isNicknameDuplicated(String nickname) {
        return userAccountUseCase.existsByNickname(nickname);
    }

    @Override
    @Transactional
    public SellerLoginResult login(SellerLoginCommand command) {
        UserView seller = findSellerByEmail(command.email());
        LocalCredential localCredential = findLocalCredential(seller.userId());

        if (!passwordEncoder.matches(command.password(), localCredential.passwordHash())) {
            throw new SellerException(SellerErrorCode.INVALID_LOGIN_CREDENTIALS);
        }

        String accessToken = jwtTokenProvider.createAccessToken(toJwtSubject(seller));
        String refreshToken = jwtTokenProvider.createRefreshToken(toJwtSubject(seller));
        refreshTokenStore.save(seller.userId(), refreshToken);

        return new SellerLoginResult(seller.userId(), accessToken, refreshToken);
    }

    @Override
    @Transactional(readOnly = true)
    public SellerTokenReissueResult reissueAccessToken(String refreshToken) {
        JwtTokenClaims claims = parseRefreshToken(refreshToken);

        UserView seller = findRefreshTokenSeller(claims.userId());
        String savedRefreshToken = refreshTokenStore.findByUserId(seller.userId())
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
        refreshTokenStore.delete(command.userId());
        Duration remainingExpiration = getRemainingExpiration(command.accessToken());

        if (remainingExpiration.isZero() || remainingExpiration.isNegative()) {
            return;
        }

        accessTokenBlacklist.save(command.accessToken(), remainingExpiration);
    }

    @Override
    @Transactional(readOnly = true)
    public SellerMeResult getMyInfo(UUID userId) {
        return toSellerMeResult(findSeller(userId));
    }

    @Override
    @Transactional
    public SellerNicknameUpdateResult updateNickname(SellerNicknameUpdateCommand command) {
        UserView seller = findSeller(command.userId());

        validateDuplicatedNickname(command.nickname());

        Instant now = timeProvider.now();
        validateNicknameChangeAllowed(seller, now);

        try {
            UserView savedSeller = userAccountUseCase.changeNickname(command.userId(), command.nickname());
            String accessToken = jwtTokenProvider.createAccessToken(toJwtSubject(savedSeller));

            return new SellerNicknameUpdateResult(toSellerMeResult(savedSeller), accessToken);
        } catch (DataIntegrityViolationException e) {
            throw new SellerException(SellerErrorCode.DUPLICATED_NICKNAME, e);
        }
    }

    private RegisterSellerCommand toRegisterCommand(SellerSignupCommand command) {
        return new RegisterSellerCommand(
                command.nickname(),
                command.name(),
                command.birthDate(),
                command.phoneNumber(),
                command.email(),
                command.marketingAgreed()
        );
    }

    private UserView findSellerByEmail(String email) {
        return userAccountUseCase.findByEmailAndRole(email, UserRole.SELLER)
                .orElseThrow(() -> new SellerException(SellerErrorCode.INVALID_LOGIN_CREDENTIALS));
    }

    private LocalCredential findLocalCredential(UUID userId) {
        return localCredentialRepository.findById(userId)
                .orElseThrow(() -> new SellerException(SellerErrorCode.INVALID_LOGIN_CREDENTIALS));
    }

    private UserView findRefreshTokenSeller(UUID userId) {
        UserView user = userAccountUseCase.findById(userId)
                .orElseThrow(() -> new SellerException(SellerErrorCode.INVALID_REFRESH_TOKEN));

        if (user.role() != UserRole.SELLER) {
            throw new SellerException(SellerErrorCode.INVALID_REFRESH_TOKEN);
        }

        return user;
    }

    private UserView findSeller(UUID userId) {
        UserView user = userAccountUseCase.findById(userId)
                .orElseThrow(() -> new SellerException(SellerErrorCode.USER_NOT_FOUND));

        if (user.role() != UserRole.SELLER) {
            throw new SellerException(SellerErrorCode.USER_NOT_FOUND);
        }

        return user;
    }

    private void validateDuplicatedNickname(String nickname) {
        if (userAccountUseCase.existsByNickname(nickname)) {
            throw new SellerException(SellerErrorCode.DUPLICATED_NICKNAME);
        }
    }

    private void validateNicknameChangeAllowed(UserView seller, Instant now) {
        // 마지막 변경 시각부터 30일이 지나야 다음 닉네임 변경을 허용한다.
        Instant nextChangeAt = seller.nicknameChangedAt().plus(NICKNAME_CHANGE_INTERVAL);
        if (now.isBefore(nextChangeAt)) {
            throw new SellerException(SellerErrorCode.NICKNAME_CHANGE_RESTRICTED);
        }
    }

    private JwtTokenClaims parseRefreshToken(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new SellerException(SellerErrorCode.INVALID_REFRESH_TOKEN);
        }

        JwtTokenClaims claims = parseToken(refreshToken);

        if (claims.tokenType() != JwtTokenType.REFRESH) {
            throw new SellerException(SellerErrorCode.INVALID_REFRESH_TOKEN);
        }

        return claims;
    }

    private JwtTokenClaims parseToken(String token) {
        try {
            return jwtTokenProvider.parseToken(token);
        } catch (RuntimeException e) {
            throw new SellerException(SellerErrorCode.INVALID_REFRESH_TOKEN, e);
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
            throw new SellerException(SellerErrorCode.INVALID_ACCESS_TOKEN, e);
        }
    }

    private JwtSubject toJwtSubject(UserView seller) {
        return new JwtSubject(seller.userId(), seller.role().name(), seller.nickname(), seller.email());
    }

    private SellerMeResult toSellerMeResult(UserView seller) {
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
