package com.sapari.seller.application.service;

import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.sapari.common.securityjwt.jwt.JwtTokenLifecycle;
import com.sapari.global.time.TimeProvider;
import com.sapari.seller.application.mapper.SellerViewMapper;
import com.sapari.seller.application.port.SellerBusinessRegistrationVerification;
import com.sapari.seller.application.port.SellerBusinessRegistrationVerifier;
import com.sapari.seller.command.SellerLoginCommand;
import com.sapari.seller.command.SellerLogoutCommand;
import com.sapari.seller.command.SellerNicknameUpdateCommand;
import com.sapari.seller.command.SellerSignupCommand;
import com.sapari.seller.domain.exception.SellerErrorCode;
import com.sapari.seller.domain.exception.SellerException;
import com.sapari.seller.domain.model.LocalCredential;
import com.sapari.seller.model.SellerBusinessType;
import com.sapari.seller.domain.model.SellerProfile;
import com.sapari.seller.domain.repository.LocalCredentialRepository;
import com.sapari.seller.domain.repository.SellerProfileRepository;
import com.sapari.seller.port.SellerAuthUseCase;
import com.sapari.seller.view.SellerLoginResult;
import com.sapari.seller.view.SellerMeView;
import com.sapari.seller.view.SellerNicknameUpdateResult;
import com.sapari.seller.view.SellerSignupResult;
import com.sapari.seller.view.SellerTokenReissueResult;
import com.sapari.user.model.UserRole;
import com.sapari.user.port.UserAccountUseCase;
import com.sapari.user.view.UserView;

@Service
@RequiredArgsConstructor
public class SellerAuthService implements SellerAuthUseCase {

    private static final Duration NICKNAME_CHANGE_INTERVAL = Duration.ofDays(30);
    private static final Duration LOGIN_LOCK_DURATION = Duration.ofMinutes(10);
    private static final int LOGIN_LOCK_THRESHOLD = 5;

    private final UserAccountUseCase userAccountUseCase;
    private final LocalCredentialRepository localCredentialRepository;
    private final SellerProfileRepository sellerProfileRepository;
    private final SellerBusinessRegistrationVerifier sellerBusinessRegistrationVerifier;
    private final SellerSignupProcessor sellerSignupProcessor;
    private final PasswordEncoder passwordEncoder;
    private final SellerJwtTokenAdapter sellerJwtTokenAdapter;
    private final TimeProvider timeProvider;
    private final SellerViewMapper sellerViewMapper;

    @Override
    public SellerSignupResult signup(SellerSignupCommand command) {
        SellerBusinessType businessType = command.businessType();
        String normalizedStoreName = normalizeStoreName(command.storeName());

        validateBusinessRegistration(command);
        validateDuplicatedStoreName(normalizedStoreName);
        validateDuplicatedBusinessNumber(command.businessNumber());

        try {
            return sellerSignupProcessor.signup(command, normalizedStoreName, businessType);
        } catch (DataIntegrityViolationException e) {
            // 트랜잭션 commit/flush 시점 unique 충돌까지 서비스 예외로 변환한다.
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
        return sellerProfileRepository.existsByStoreName(normalizeStoreName(storeName));
    }

    @Override
    // 비밀번호 실패 이력은 인증 실패 응답을 반환하더라도 커밋되어야 하므로 SellerException으로 rollback하지 않는다.
    @Transactional(noRollbackFor = SellerException.class)
    public SellerLoginResult login(SellerLoginCommand command) {
        UserView seller = findSellerByEmail(command.email());
        LocalCredential localCredential = findLocalCredentialForUpdate(seller.userId());
        Instant now = timeProvider.now();

        // 잠금 중에는 계정 존재 여부나 잠금 상태를 드러내지 않고 비밀번호 검증 전에 차단한다.
        if (localCredential.isLocked(now, LOGIN_LOCK_DURATION)) {
            throw new SellerException(SellerErrorCode.INVALID_LOGIN_CREDENTIALS);
        }

        // 잠금이 만료된 계정은 실패 횟수를 새 로그인 시도 기준으로 다시 계산한다.
        LocalCredential loginAttemptCredential = resetExpiredLock(localCredential, now);
        if (!passwordEncoder.matches(command.password(), loginAttemptCredential.passwordHash())) {
            localCredentialRepository.save(loginAttemptCredential.recordLoginFailure(now, LOGIN_LOCK_THRESHOLD));
            throw new SellerException(SellerErrorCode.INVALID_LOGIN_CREDENTIALS);
        }

        // 로그인 성공 시 연속 실패 이력이나 잠금 상태가 남아 있는 경우에만 초기화한다.
        if (loginAttemptCredential.hasLoginFailureHistory()) {
            localCredentialRepository.save(loginAttemptCredential.resetLoginFailures());
        }
        JwtTokenLifecycle.IssuedTokenPair tokenPair = sellerJwtTokenAdapter.issueTokenPair(seller);

        return new SellerLoginResult(seller.userId(), tokenPair.accessToken(), tokenPair.refreshToken());
    }

    @Override
    @Transactional
    public SellerTokenReissueResult reissueAccessToken(String refreshToken) {
        JwtTokenLifecycle.RefreshSession refreshSession = sellerJwtTokenAdapter.requireRefreshToken(refreshToken);
        UserView seller = findRefreshTokenSeller(refreshSession.userId());
        JwtTokenLifecycle.RotatedRefreshToken rotatedRefreshToken =
                sellerJwtTokenAdapter.rotateRefreshToken(refreshSession, seller);

        return new SellerTokenReissueResult(
                seller.userId(),
                rotatedRefreshToken.accessToken(),
                rotatedRefreshToken.refreshToken(),
                rotatedRefreshToken.refreshTokenMaxAgeSeconds()
        );
    }

    @Override
    @Transactional
    public void logout(SellerLogoutCommand command) {
        sellerJwtTokenAdapter.revokeSession(command.accessToken());
    }

    @Override
    @Transactional(readOnly = true)
    public SellerMeView getMyInfo(UUID userId) {
        UserView seller = findSeller(userId);
        SellerProfile sellerProfile = findSellerProfile(userId);

        return sellerViewMapper.toMeView(seller, sellerProfile);
    }

    @Override
    @Transactional
    public SellerNicknameUpdateResult updateNickname(SellerNicknameUpdateCommand command) {
        JwtTokenLifecycle.AccessSession accessSession =
                sellerJwtTokenAdapter.requireAccessToken(command.accessToken());
        UserView seller = findSeller(accessSession.userId());

        validateDuplicatedNickname(command.nickname());

        Instant now = timeProvider.now();
        validateNicknameChangeAllowed(seller, now);

        try {
            UserView savedSeller = userAccountUseCase.changeNickname(accessSession.userId(), command.nickname());
            SellerProfile sellerProfile = findSellerProfile(accessSession.userId());
            String accessToken = sellerJwtTokenAdapter.replaceAccessTokenForNickname(accessSession, savedSeller);

            return sellerViewMapper.toNicknameUpdateResult(savedSeller, sellerProfile, accessToken);
        } catch (DataIntegrityViolationException e) {
            throw new SellerException(SellerErrorCode.DUPLICATED_NICKNAME, e);
        }
    }

    private void validateDuplicatedBusinessNumber(String businessNumber) {
        if (sellerProfileRepository.existsByBusinessNumber(businessNumber)) {
            throw new SellerException(SellerErrorCode.DUPLICATED_BUSINESS_NUMBER);
        }
    }

    private void validateDuplicatedStoreName(String storeName) {
        if (!StringUtils.hasText(storeName)) {
            return;
        }

        if (sellerProfileRepository.existsByStoreName(storeName)) {
            throw new SellerException(SellerErrorCode.DUPLICATED_STORE_NAME);
        }
    }

    /**
     * 판매자 가입 전에 사업자등록정보가 국세청 정보와 일치하고 계속사업자 상태인지 확인한다.
     */
    private void validateBusinessRegistration(SellerSignupCommand command) {
        SellerBusinessRegistrationVerification verification = sellerBusinessRegistrationVerifier.verify(
                command.businessNumber(),
                command.name(),
                command.businessStartDate()
        );
        if (verification == null) {
            throw new SellerException(SellerErrorCode.BUSINESS_REGISTRATION_CHECK_UNAVAILABLE);
        }

        if (verification.registrationAvailable()) {
            return;
        }

        if (verification.failureReason() == SellerBusinessRegistrationVerification.FailureReason.UNAVAILABLE) {
            throw new SellerException(SellerErrorCode.BUSINESS_REGISTRATION_CHECK_UNAVAILABLE);
        }

        throw new SellerException(SellerErrorCode.INVALID_BUSINESS_REGISTRATION);
    }

    private UserView findSellerByEmail(String email) {
        return userAccountUseCase.findByEmailAndRole(email, UserRole.SELLER)
                .orElseThrow(() -> new SellerException(SellerErrorCode.INVALID_LOGIN_CREDENTIALS));
    }

    private LocalCredential findLocalCredentialForUpdate(UUID userId) {
        // 같은 판매자 계정의 동시 로그인 실패 시도도 실패 횟수가 순차 반영되도록 row lock을 사용한다.
        return localCredentialRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new SellerException(SellerErrorCode.INVALID_LOGIN_CREDENTIALS));
    }

    private LocalCredential resetExpiredLock(LocalCredential localCredential, Instant now) {
        if (localCredential.lockedAt() == null) {
            return localCredential;
        }

        if (localCredential.isLocked(now, LOGIN_LOCK_DURATION)) {
            return localCredential;
        }

        return localCredential.resetLoginFailures();
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
                .orElseThrow(() -> new SellerException(SellerErrorCode.SELLER_PROFILE_NOT_FOUND));
    }

    private String normalizeStoreName(String storeName) {
        if (storeName == null) {
            return null;
        }

        return storeName.trim();
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

}
