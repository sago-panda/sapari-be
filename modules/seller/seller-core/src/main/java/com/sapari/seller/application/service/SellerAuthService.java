package com.sapari.seller.application.service;

import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sapari.common.securityjwt.jwt.JwtSubject;
import com.sapari.common.securityjwt.jwt.JwtTokenClaims;
import com.sapari.common.securityjwt.jwt.JwtTokenProvider;
import com.sapari.common.securityjwt.jwt.JwtTokenType;
import com.sapari.global.time.TimeProvider;
import com.sapari.seller.command.SellerLoginCommand;
import com.sapari.seller.command.SellerLogoutCommand;
import com.sapari.seller.command.SellerNicknameUpdateCommand;
import com.sapari.seller.command.SellerSignupCommand;
import com.sapari.seller.domain.exception.SellerErrorCode;
import com.sapari.seller.domain.exception.SellerException;
import com.sapari.seller.domain.model.LocalCredential;
import com.sapari.seller.domain.model.SellerBusinessType;
import com.sapari.seller.domain.model.SellerProfile;
import com.sapari.seller.domain.repository.LocalCredentialRepository;
import com.sapari.seller.domain.repository.SellerProfileRepository;
import com.sapari.seller.port.SellerAuthUseCase;
import com.sapari.seller.result.SellerLoginResult;
import com.sapari.seller.result.SellerMeResult;
import com.sapari.seller.result.SellerNicknameUpdateResult;
import com.sapari.seller.result.SellerSignupResult;
import com.sapari.seller.result.SellerTokenReissueResult;
import com.sapari.user.command.RegisterSellerCommand;
import com.sapari.common.securityjwt.store.AccessTokenBlacklist;
import com.sapari.common.securityjwt.store.RefreshTokenStore;
import com.sapari.common.securityjwt.store.SessionRevocationStore;
import com.sapari.user.model.UserRole;
import com.sapari.user.port.UserAccountUseCase;
import com.sapari.user.view.UserView;

@Service
@RequiredArgsConstructor
public class SellerAuthService implements SellerAuthUseCase {

    private static final Duration NICKNAME_CHANGE_INTERVAL = Duration.ofDays(30);

    private final UserAccountUseCase userAccountUseCase;
    private final LocalCredentialRepository localCredentialRepository;
    private final SellerProfileRepository sellerProfileRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenStore refreshTokenStore;
    private final SessionRevocationStore sessionRevocationStore;
    private final AccessTokenBlacklist accessTokenBlacklist;
    private final TimeProvider timeProvider;

    @Override
    @Transactional
    public SellerSignupResult signup(SellerSignupCommand command) {
        validateDuplicatedStoreName(command.storeName());
        validateDuplicatedBusinessNumber(command.businessNumber());
        SellerBusinessType businessType = toBusinessType(command.businessType());

        try {
            UserView savedUser = userAccountUseCase.registerSeller(toRegisterCommand(command));
            LocalCredential localCredential = LocalCredential.create(
                    savedUser.userId(),
                    passwordEncoder.encode(command.password()),
                    timeProvider.now()
            );
            SellerProfile sellerProfile = SellerProfile.createPending(
                    savedUser.userId(),
                    command.storeName(),
                    command.businessNumber(),
                    businessType
            );

            localCredentialRepository.save(localCredential);
            sellerProfileRepository.save(sellerProfile);

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
    @Transactional(readOnly = true)
    public boolean isStoreNameDuplicated(String storeName) {
        return sellerProfileRepository.existsByStoreName(storeName);
    }

    @Override
    @Transactional
    public SellerLoginResult login(SellerLoginCommand command) {
        UserView seller = findSellerByEmail(command.email());
        LocalCredential localCredential = findLocalCredential(seller.userId());

        if (!passwordEncoder.matches(command.password(), localCredential.passwordHash())) {
            throw new SellerException(SellerErrorCode.INVALID_LOGIN_CREDENTIALS);
        }

        UUID sessionId = UUID.randomUUID();
        JwtSubject subject = toJwtSubject(seller, sessionId);
        String accessToken = jwtTokenProvider.createAccessToken(subject);
        String refreshToken = issueRefreshToken(subject);

        return new SellerLoginResult(seller.userId(), accessToken, refreshToken);
    }

    @Override
    @Transactional
    public SellerTokenReissueResult reissueAccessToken(String refreshToken) {
        JwtTokenClaims claims = parseRefreshToken(refreshToken);

        UserView seller = findRefreshTokenSeller(claims.userId());
        JwtSubject subject = toJwtSubject(seller, claims.sessionId());
        String accessToken = jwtTokenProvider.createAccessToken(subject);
        RotatedRefreshToken rotatedRefreshToken = rotateRefreshToken(subject, claims);

        return new SellerTokenReissueResult(
                seller.userId(),
                accessToken,
                rotatedRefreshToken.token(),
                rotatedRefreshToken.maxAgeSeconds()
        );
    }

    @Override
    @Transactional
    public void logout(SellerLogoutCommand command) {
        JwtTokenClaims claims = parseAccessToken(command.accessToken());
        validateAccessTokenOwner(claims, command.userId());

        // 로그아웃은 현재 sid 세션 전체를 폐기해 같은 세션의 Access Token까지 차단한다.
        refreshTokenStore.deleteBySessionId(claims.sessionId());
        sessionRevocationStore.revoke(claims.sessionId());
    }

    @Override
    @Transactional(readOnly = true)
    public SellerMeResult getMyInfo(UUID userId) {
        UserView seller = findSeller(userId);
        SellerProfile sellerProfile = findSellerProfile(userId);

        return toSellerMeResult(seller, sellerProfile);
    }

    @Override
    @Transactional
    public SellerNicknameUpdateResult updateNickname(SellerNicknameUpdateCommand command) {
        JwtTokenClaims accessClaims = parseAccessToken(command.accessToken());
        validateAccessTokenOwner(accessClaims, command.userId());

        UserView seller = findSeller(command.userId());

        validateDuplicatedNickname(command.nickname());

        Instant now = timeProvider.now();
        validateNicknameChangeAllowed(seller, now);

        try {
            UserView savedSeller = userAccountUseCase.changeNickname(command.userId(), command.nickname());
            SellerProfile sellerProfile = findSellerProfile(command.userId());
            // nickname snapshot이 바뀌었으므로 기존 access jti를 폐기하고 같은 sid로 새 Access Token을 발급
            blacklistAccessToken(accessClaims);
            String accessToken = jwtTokenProvider.createAccessToken(toJwtSubject(savedSeller, accessClaims.sessionId()));

            return new SellerNicknameUpdateResult(toSellerMeResult(savedSeller, sellerProfile), accessToken);
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

    private void validateDuplicatedBusinessNumber(String businessNumber) {
        if (sellerProfileRepository.existsByBusinessNumber(businessNumber)) {
            throw new SellerException(SellerErrorCode.DUPLICATED_BUSINESS_NUMBER);
        }
    }

    private void validateDuplicatedStoreName(String storeName) {
        if (sellerProfileRepository.existsByStoreName(storeName)) {
            throw new SellerException(SellerErrorCode.DUPLICATED_STORE_NAME);
        }
    }

    private SellerBusinessType toBusinessType(String businessType) {
        if (businessType == null || businessType.isBlank()) {
            throw new SellerException(SellerErrorCode.INVALID_BUSINESS_TYPE);
        }

        try {
            return SellerBusinessType.valueOf(businessType);
        } catch (IllegalArgumentException e) {
            throw new SellerException(SellerErrorCode.INVALID_BUSINESS_TYPE, e);
        }
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

    private SellerProfile findSellerProfile(UUID userId) {
        return sellerProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new SellerException(SellerErrorCode.USER_NOT_FOUND));
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

        JwtTokenClaims claims = parseToken(refreshToken, SellerErrorCode.INVALID_REFRESH_TOKEN);

        if (claims.tokenType() != JwtTokenType.REFRESH) {
            throw new SellerException(SellerErrorCode.INVALID_REFRESH_TOKEN);
        }

        return claims;
    }

    private JwtTokenClaims parseAccessToken(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new SellerException(SellerErrorCode.INVALID_ACCESS_TOKEN);
        }

        JwtTokenClaims claims = parseToken(accessToken, SellerErrorCode.INVALID_ACCESS_TOKEN);

        if (claims.tokenType() != JwtTokenType.ACCESS) {
            throw new SellerException(SellerErrorCode.INVALID_ACCESS_TOKEN);
        }

        return claims;
    }

    private JwtTokenClaims parseToken(String token, SellerErrorCode errorCode) {
        try {
            return jwtTokenProvider.parseToken(token);
        } catch (RuntimeException e) {
            throw new SellerException(errorCode, e);
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
            throw new SellerException(SellerErrorCode.INVALID_REFRESH_TOKEN);
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
            throw new SellerException(SellerErrorCode.INVALID_REFRESH_TOKEN);
        }

        return new RotatedRefreshToken(refreshToken, refreshTokenTtl.toSeconds());
    }

    private void validateAccessTokenOwner(JwtTokenClaims claims, UUID userId) {
        if (!claims.userId().equals(userId)) {
            throw new SellerException(SellerErrorCode.INVALID_ACCESS_TOKEN);
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

    private JwtSubject toJwtSubject(UserView seller, UUID sessionId) {
        return new JwtSubject(seller.userId(), sessionId, seller.role().name(), seller.nickname(), seller.email());
    }

    private SellerMeResult toSellerMeResult(UserView seller, SellerProfile sellerProfile) {
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
                seller.marketingAgreed(),
                sellerProfile.storeName(),
                sellerProfile.businessNumber(),
                sellerProfile.businessType().name(),
                sellerProfile.status().name(),
                sellerProfile.rejectionReason(),
                sellerProfile.approvedAt()
        );
    }

    private record RotatedRefreshToken(
            String token,
            long maxAgeSeconds
    ) {
    }
}
