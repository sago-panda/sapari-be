package com.sapari.customer.application.service;

import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import com.sapari.common.core.exception.BusinessException;
import com.sapari.common.securityjwt.jwt.JwtTokenLifecycle;
import com.sapari.customer.application.dto.SocialSignupInfo;
import com.sapari.customer.application.mapper.CustomerViewMapper;
import com.sapari.customer.application.port.SocialProfileImageDownloader;
import com.sapari.customer.command.CustomerLogoutCommand;
import com.sapari.customer.command.CustomerEmailVerificationConfirmCommand;
import com.sapari.customer.command.CustomerEmailVerificationSendCommand;
import com.sapari.customer.command.CustomerNicknameUpdateCommand;
import com.sapari.customer.command.CustomerPhoneVerificationConfirmCommand;
import com.sapari.customer.command.CustomerPhoneVerificationSendCommand;
import com.sapari.customer.command.CustomerProfileImageChangeCommand;
import com.sapari.customer.command.SocialSignupCommand;
import com.sapari.customer.domain.exception.CustomerErrorCode;
import com.sapari.customer.domain.exception.CustomerException;
import com.sapari.customer.domain.repository.SocialLoginCodeRepository;
import com.sapari.customer.domain.repository.SocialSignupAttemptRepository;
import com.sapari.customer.domain.repository.SocialSignupRepository;
import com.sapari.customer.port.CustomerAuthUseCase;
import com.sapari.customer.view.CustomerMeView;
import com.sapari.customer.view.CustomerEmailVerificationConfirmResult;
import com.sapari.customer.view.CustomerEmailVerificationSendResult;
import com.sapari.customer.view.CustomerNicknameUpdateResult;
import com.sapari.customer.view.CustomerPhoneVerificationConfirmResult;
import com.sapari.customer.view.CustomerPhoneVerificationSendResult;
import com.sapari.customer.view.CustomerTokenReissueResult;
import com.sapari.customer.view.SocialSignupInfoView;
import com.sapari.customer.view.SocialLoginTokenResult;
import com.sapari.customer.view.SocialSignupResult;
import com.sapari.global.time.TimeProvider;
import com.sapari.user.command.ProfileImageChangeCommand;
import com.sapari.user.command.ProfileImagePrepareCommand;
import com.sapari.user.command.RegisterSocialCustomerCommand;
import com.sapari.user.command.SocialCustomerRegistrationRollbackCommand;
import com.sapari.user.model.UserGender;
import com.sapari.user.model.UserRole;
import com.sapari.user.model.UserStatus;
import com.sapari.user.port.UserAccountUseCase;
import com.sapari.user.view.UserView;
import com.sapari.user.view.PreparedProfileImage;

@Service
@RequiredArgsConstructor
public class CustomerAuthService implements CustomerAuthUseCase {

    private static final Duration NICKNAME_CHANGE_INTERVAL = Duration.ofDays(30);

    private final SocialSignupRepository socialSignupRepository;
    private final SocialLoginCodeRepository socialLoginCodeRepository;
    private final UserAccountUseCase userAccountUseCase;
    private final CustomerJwtTokenAdapter customerJwtTokenAdapter;
    private final TimeProvider timeProvider;
    private final ObjectMapper objectMapper;
    private final CustomerViewMapper customerViewMapper;
    private final CustomerSignupContactVerificationAdapter signupContactVerificationAdapter;
    private final SocialProfileImageDownloader socialProfileImageDownloader;
    private final SocialSignupAttemptRepository socialSignupAttemptRepository;

    /**
     * signup sid로 임시 소셜 정보를 조회하고 추가정보를 합쳐 구매자 가입을 완료한다.
     * SID는 성공 시점까지 유지하고 별도 processing lock으로 중복 실행을 막으며, 실패 시 재시도를 위해 보존한다.
     * 이미지 반영까지 끝난 뒤 서버가 보관한 휴대폰·이메일 verified 상태를 함께 소비해 가입을 확정한다.
     * 직접 업로드 파일 검증/저장 실패는 회원가입 실패로 전파해 잘못된 파일을 조용히 무시하지 않는다.
     */
    @Override
    public SocialSignupResult completeSocialSignup(String signupSid, SocialSignupCommand command) {
        SocialSignupInfo socialSignupInfo = findSocialSignupInfo(signupSid);
        validateProfileImageChoice(command);
        SocialSignupAttemptRepository.AcquireResult acquireResult = socialSignupAttemptRepository.tryAcquire(signupSid);
        if (acquireResult.status() == SocialSignupAttemptRepository.AcquireStatus.ALREADY_PROCESSING) {
            throw new CustomerException(CustomerErrorCode.SOCIAL_SIGNUP_ALREADY_PROCESSING);
        }
        if (acquireResult.status() == SocialSignupAttemptRepository.AcquireStatus.RATE_LIMIT_EXCEEDED) {
            throw new CustomerException(CustomerErrorCode.SOCIAL_SIGNUP_RATE_LIMIT_EXCEEDED);
        }

        RuntimeException primaryFailure = null;
        try {
            // 비싼 이미지 준비는 동시 실행과 시도 한도를 통과한 요청만 수행한다.
            PreparedSignupProfileImage profileImage = prepareSignupProfileImageChoice(command, socialSignupInfo);

            // userId 기반 object key가 필요하므로 이미지는 검증만 마친 상태에서 회원을 먼저 생성한다.
            UserView savedUser;
            try {
                savedUser = userAccountUseCase.registerSocialCustomer(toRegisterCommand(command, socialSignupInfo));
            } catch (DataIntegrityViolationException e) {
                throw new CustomerException(CustomerErrorCode.DUPLICATED_SIGNUP_INFO, e);
            }

            UserView profileImageAppliedUser;
            try {
                profileImageAppliedUser = applySignupProfileImageChoice(savedUser, profileImage);
                // 저장소와 DB 후속 처리가 끝난 뒤 one-time 인증 상태를 소비해 가입을 확정한다.
                signupContactVerificationAdapter.consumePhoneAndEmailVerification(command.phoneNumber(), command.email());
            } catch (RuntimeException e) {
                // 사용자 생성 이후 인증 소비 전까지의 실패는 이번 요청이 만든 가입 데이터만 보상한다.
                rollbackSocialCustomerRegistration(savedUser, command, socialSignupInfo, e);
                throw e;
            }

            deleteCompletedSocialSignupSid(signupSid);
            JwtTokenLifecycle.IssuedTokenPair tokenPair = customerJwtTokenAdapter.issueTokenPair(profileImageAppliedUser);

            return new SocialSignupResult(profileImageAppliedUser.userId(), tokenPair.accessToken(), tokenPair.refreshToken());
        } catch (RuntimeException e) {
            primaryFailure = e;
            throw e;
        } finally {
            try {
                socialSignupAttemptRepository.release(signupSid, acquireResult.leaseToken());
            } catch (RuntimeException cleanupFailure) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        }
    }

    private void deleteCompletedSocialSignupSid(String signupSid) {
        try {
            socialSignupRepository.delete(signupSid);
        } catch (DataAccessException e) {
            throw new CustomerException(CustomerErrorCode.SOCIAL_SIGNUP_ATTEMPT_CONTROL_UNAVAILABLE, e);
        }
    }

    /**
     * 구매자 회원가입 휴대폰 인증번호를 발송한다.
     * 가입 가능 번호 확인, 쿨다운, 발송·저장 정책은 user가 처리하되 customer API 오류 계약으로 변환해 반환한다.
     */
    @Override
    public CustomerPhoneVerificationSendResult sendSignupPhoneVerification(CustomerPhoneVerificationSendCommand command) {
        return signupContactVerificationAdapter.sendPhoneVerification(command);
    }

    /**
     * 구매자 회원가입 휴대폰 인증번호를 확인한다.
     * 인증 성공 시 회원가입 API가 소비할 Redis verified 상태를 생성하고, 실패 사유는 CUSTOMER-* 코드로 노출한다.
     */
    @Override
    public CustomerPhoneVerificationConfirmResult confirmSignupPhoneVerification(CustomerPhoneVerificationConfirmCommand command) {
        return signupContactVerificationAdapter.confirmPhoneVerification(command);
    }

    /**
     * 구매자 회원가입 이메일 인증번호를 발송한다.
     * 가입 가능 이메일 확인, 쿨다운, 발송·저장 정책은 user가 처리하되 customer API 오류 계약으로 변환해 반환한다.
     */
    @Override
    public CustomerEmailVerificationSendResult sendSignupEmailVerification(CustomerEmailVerificationSendCommand command) {
        return signupContactVerificationAdapter.sendEmailVerification(command);
    }

    /**
     * 구매자 회원가입 이메일 인증번호를 확인한다.
     * 인증 성공 시 회원가입 API가 소비할 Redis verified 상태를 생성하고, 실패 사유는 CUSTOMER-* 코드로 노출한다.
     */
    @Override
    public CustomerEmailVerificationConfirmResult confirmSignupEmailVerification(CustomerEmailVerificationConfirmCommand command) {
        return signupContactVerificationAdapter.confirmEmailVerification(command);
    }

    @Override
    @Transactional(readOnly = true)
    public SocialSignupInfoView getSocialSignupInfo(String signupSid) {
        return customerViewMapper.toSocialSignupInfoView(findSocialSignupInfo(signupSid));
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

        // temporary_login_code는 토큰 교환용 one-time code이므로 Redis GETDEL 기반 consume으로 중복 교환을 막는다.
        return socialLoginCodeRepository.consumeByCode(temporaryLoginCode)
                .map(this::readSocialLoginTokenResult)
                .orElseThrow(() -> new CustomerException(CustomerErrorCode.INVALID_LOGIN_CODE));
    }

    /**
     * Refresh Token을 검증해 새 Access Token을 발급
     */
    @Override
    @Transactional
    public CustomerTokenReissueResult reissueAccessToken(String refreshToken) {
        JwtTokenLifecycle.RefreshSession refreshSession = customerJwtTokenAdapter.requireRefreshToken(refreshToken);
        UserView customer = findRefreshTokenCustomer(refreshSession.userId());
        JwtTokenLifecycle.RotatedRefreshToken rotatedRefreshToken =
                customerJwtTokenAdapter.rotateRefreshToken(refreshSession, customer);

        return new CustomerTokenReissueResult(
                customer.userId(),
                rotatedRefreshToken.accessToken(),
                rotatedRefreshToken.refreshToken(),
                rotatedRefreshToken.refreshTokenMaxAgeSeconds()
        );
    }

    @Override
    @Transactional
    public void logout(CustomerLogoutCommand command) {
        customerJwtTokenAdapter.revokeSession(command.accessToken());
    }

    /**
     * Access Token을 검증해 고객 계정을 탈퇴 유예 상태로 전환하고 모든 refresh/access 세션을 폐기한다.
     */
    @Override
    @Transactional
    public void requestWithdrawal(String accessToken) {
        JwtTokenLifecycle.AccessSession accessSession =
                customerJwtTokenAdapter.requireAccessToken(accessToken);
        UserView customer = findCustomer(accessSession.userId());

        userAccountUseCase.requestWithdrawal(customer.userId());
        // 회원 탈퇴는 현재 기기 로그아웃이 아니라 모든 기기 세션을 즉시 폐기해야 한다.
        customerJwtTokenAdapter.revokeAllSessions(customer.userId());
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
    public CustomerMeView getMyInfo(UUID userId) {
        return customerViewMapper.toMeView(findCustomer(userId));
    }

    @Override
    @Transactional
    public CustomerNicknameUpdateResult updateNickname(CustomerNicknameUpdateCommand command) {
        JwtTokenLifecycle.AccessSession accessSession =
                customerJwtTokenAdapter.requireAccessToken(command.accessToken());
        UserView customer = findCustomer(accessSession.userId());

        validateDuplicatedNickname(command.nickname());

        Instant now = timeProvider.now();
        validateNicknameChangeAllowed(customer, now);

        try {
            UserView savedCustomer = userAccountUseCase.changeNickname(accessSession.userId(), command.nickname());
            String accessToken = customerJwtTokenAdapter.replaceAccessTokenForNickname(accessSession, savedCustomer);

            return customerViewMapper.toNicknameUpdateResult(savedCustomer, accessToken);
        } catch (DataIntegrityViolationException e) {
            throw new CustomerException(CustomerErrorCode.DUPLICATED_NICKNAME, e);
        }
    }

    /** Access Token의 고객 소유권을 확인한 뒤 user 프로필 이미지 저장 흐름을 호출한다. */
    @Override
    public CustomerMeView updateProfileImage(CustomerProfileImageChangeCommand command) {
        JwtTokenLifecycle.AccessSession accessSession =
                customerJwtTokenAdapter.requireAccessToken(command.accessToken());
        findCustomer(accessSession.userId());

        UserView savedCustomer = userAccountUseCase.changeProfileImage(new ProfileImageChangeCommand(
                accessSession.userId(),
                command.originalFilename(),
                command.contentType(),
                command.content()
        ));

        return customerViewMapper.toMeView(savedCustomer);
    }

    /** Access Token의 고객 소유권을 확인한 뒤 DB key 제거와 기존 object 정리를 요청한다. */
    @Override
    public CustomerMeView deleteProfileImage(String accessToken) {
        JwtTokenLifecycle.AccessSession accessSession =
                customerJwtTokenAdapter.requireAccessToken(accessToken);
        findCustomer(accessSession.userId());

        UserView savedCustomer = userAccountUseCase.removeProfileImage(accessSession.userId());
        return customerViewMapper.toMeView(savedCustomer);
    }

    private RegisterSocialCustomerCommand toRegisterCommand(SocialSignupCommand command, SocialSignupInfo socialSignupInfo) {
        return new RegisterSocialCustomerCommand(
                command.nickname(),
                command.name(),
                command.birthDate(),
                UserGender.valueOf(command.gender()),
                command.phoneNumber(),
                command.email(),
                null,
                command.privacyAgreed(),
                command.marketingAgreed(),
                socialSignupInfo.provider(),
                socialSignupInfo.providerId(),
                socialSignupInfo.providerEmail()
        );
    }

    /**
     * OAuth 콜백 이후 Redis에 임시 보관한 소셜 가입 정보를 조회한다.
     */
    private SocialSignupInfo findSocialSignupInfo(String signupSid) {
        if (signupSid == null || signupSid.isBlank()) {
            throw new CustomerException(CustomerErrorCode.INVALID_SIGNUP_SESSION);
        }

        return socialSignupRepository.findBySid(signupSid)
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

    /** 직접 업로드와 provider 이미지 사용을 동시에 선택하는 모호한 요청을 거부한다. */
    private void validateProfileImageChoice(SocialSignupCommand command) {
        if (command.hasUploadedProfileImage() && command.useSocialProfileImage()) {
            throw new CustomerException(CustomerErrorCode.INVALID_PROFILE_IMAGE_CHOICE);
        }
    }

    /**
     * DB 가입 전에 직접 업로드 이미지를 검증하거나 서버 보관 provider URL의 이미지를 내려받아 정규화한다.
     * 선택한 provider 이미지의 URL이 없거나 이미지를 준비하지 못하면 가입을 중단한다.
     */
    private PreparedSignupProfileImage prepareSignupProfileImageChoice(
            SocialSignupCommand command,
            SocialSignupInfo socialSignupInfo
    ) {
        if (command.hasUploadedProfileImage()) {
            // 직접 업로드는 회원 생성 전에 검증·재인코딩해 잘못된 파일로 가입 데이터가 남지 않게 한다.
            PreparedProfileImage image = userAccountUseCase.prepareProfileImage(new ProfileImagePrepareCommand(
                    command.profileImageOriginalFilename(),
                    command.profileImageContentType(),
                    command.profileImageContent()
            ));
            return new PreparedSignupProfileImage(image);
        }

        if (!command.useSocialProfileImage()) {
            return null;
        }
        if (socialSignupInfo.profileImageUrl() == null || socialSignupInfo.profileImageUrl().isBlank()) {
            // 사용자가 선택한 소셜 이미지를 URL이 없다는 이유로 이미지 없는 가입으로 바꾸지 않는다.
            throw new CustomerException(CustomerErrorCode.SOCIAL_PROFILE_IMAGE_IMPORT_FAILED);
        }

        // 클라이언트 입력 URL이 아니라 OAuth callback에서 서버가 보관한 provider URL만 다운로드한다.
        return socialProfileImageDownloader.download(socialSignupInfo.provider(), socialSignupInfo.profileImageUrl())
                .map(image -> new PreparedSignupProfileImage(
                        new PreparedProfileImage(
                                image.normalizedExtension(),
                                image.contentType(),
                                image.content()
                        )
                ))
                .orElseThrow(() -> new CustomerException(CustomerErrorCode.SOCIAL_PROFILE_IMAGE_IMPORT_FAILED));
    }

    /** 이미지 저장 오류만 customer 계약으로 변환하고, 가입 보상은 상위 orchestration에 맡긴다. */
    private UserView applySignupProfileImageChoice(
            UserView savedUser,
            PreparedSignupProfileImage profileImage
    ) {
        if (profileImage == null) {
            return savedUser;
        }

        try {
            // 회원 생성으로 확보한 userId를 결합해 여기서 object key 생성·업로드·DB key 반영을 완료한다.
            return userAccountUseCase.changePreparedProfileImage(savedUser.userId(), profileImage.image());
        } catch (BusinessException e) {
            if (isProfileImageStorageUnavailable(e)) {
                throw new CustomerException(CustomerErrorCode.PROFILE_IMAGE_STORAGE_UNAVAILABLE, e);
            }
            throw e;
        }
    }

    private boolean isProfileImageStorageUnavailable(BusinessException exception) {
        // customer-core는 user-core를 의존하지 않으므로 user 공개 오류 코드로 저장소 실패만 구분한다.
        return "USER-118".equals(exception.getErrorCode().getCode());
    }

    /**
     * 이번 요청이 생성한 사용자만 식별자 대조 후 보상 삭제한다.
     * 보상 실패는 원래 실패 원인을 보존하기 위해 suppressed exception으로 연결한다.
     */
    private void rollbackSocialCustomerRegistration(
            UserView savedUser,
            SocialSignupCommand command,
            SocialSignupInfo socialSignupInfo,
            RuntimeException originalException
    ) {
        try {
            userAccountUseCase.rollbackSocialCustomerRegistration(new SocialCustomerRegistrationRollbackCommand(
                    savedUser.userId(),
                    socialSignupInfo.provider(),
                    socialSignupInfo.providerId(),
                    command.email()
            ));
        } catch (RuntimeException rollbackException) {
            originalException.addSuppressed(rollbackException);
        }
    }

    /** 가입 전 검증·정규화가 끝난 프로필 이미지다. */
    private record PreparedSignupProfileImage(PreparedProfileImage image) {
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

        if (user.role() != UserRole.USER || user.status() != UserStatus.ACTIVE) {
            throw new CustomerException(CustomerErrorCode.INVALID_REFRESH_TOKEN);
        }

        return user;
    }

}
