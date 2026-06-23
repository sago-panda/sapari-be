package com.sapari.customer.application.service;

import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import com.sapari.common.securityjwt.jwt.JwtTokenLifecycle;
import com.sapari.customer.application.dto.SocialSignupInfo;
import com.sapari.customer.application.mapper.CustomerViewMapper;
import com.sapari.customer.command.CustomerLogoutCommand;
import com.sapari.customer.command.CustomerNicknameUpdateCommand;
import com.sapari.customer.command.CustomerPhoneVerificationConfirmCommand;
import com.sapari.customer.command.CustomerPhoneVerificationSendCommand;
import com.sapari.customer.command.SocialSignupCommand;
import com.sapari.customer.domain.exception.CustomerErrorCode;
import com.sapari.customer.domain.exception.CustomerException;
import com.sapari.customer.domain.repository.SocialLoginCodeRepository;
import com.sapari.customer.domain.repository.SocialSignupRepository;
import com.sapari.customer.port.CustomerAuthUseCase;
import com.sapari.customer.view.CustomerMeView;
import com.sapari.customer.view.CustomerNicknameUpdateResult;
import com.sapari.customer.view.CustomerPhoneVerificationConfirmResult;
import com.sapari.customer.view.CustomerPhoneVerificationSendResult;
import com.sapari.customer.view.CustomerTokenReissueResult;
import com.sapari.customer.view.SocialSignupInfoView;
import com.sapari.customer.view.SocialLoginTokenResult;
import com.sapari.customer.view.SocialSignupResult;
import com.sapari.global.time.TimeProvider;
import com.sapari.user.command.RegisterSocialCustomerCommand;
import com.sapari.user.model.UserGender;
import com.sapari.user.model.UserRole;
import com.sapari.user.model.UserStatus;
import com.sapari.user.port.UserAccountUseCase;
import com.sapari.user.view.UserView;

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
    private final CustomerPhoneVerificationService customerPhoneVerificationService;

    /**
     * signup sid로 임시 소셜 정보를 조회하고 추가정보를 합쳐 구매자 가입
     */
    @Override
    @Transactional
    public SocialSignupResult completeSocialSignup(String signupSid, SocialSignupCommand command) {
        SocialSignupInfo socialSignupInfo = findSocialSignupInfo(signupSid);
        // 회원가입 요청의 phoneVerified 값은 위조 가능하므로 Redis verified 상태만 서버 기준으로 소비한다.
        // Redis GETDEL은 DB 트랜잭션과 함께 롤백되지 않으므로, 이후 가입 저장 실패 시 사용자는 재인증해야 한다.
        customerPhoneVerificationService.consumeSignupVerification(command.phoneNumber());

        try {
            UserView savedUser = userAccountUseCase.registerSocialCustomer(toRegisterCommand(command, socialSignupInfo));
            socialSignupRepository.delete(signupSid);
            JwtTokenLifecycle.IssuedTokenPair tokenPair = customerJwtTokenAdapter.issueTokenPair(savedUser);

            return new SocialSignupResult(savedUser.userId(), tokenPair.accessToken(), tokenPair.refreshToken());
        } catch (DataIntegrityViolationException e) {
            throw new CustomerException(CustomerErrorCode.DUPLICATED_SIGNUP_INFO, e);
        }
    }

    /**
     * 구매자 회원가입 휴대폰 인증번호를 발송한다.
     * 쿨다운 선점, SOLAPI 발송, codeHash 저장 정책은 휴대폰 인증 서비스가 처리한다.
     */
    @Override
    public CustomerPhoneVerificationSendResult sendSignupPhoneVerification(CustomerPhoneVerificationSendCommand command) {
        return customerPhoneVerificationService.sendSignupCode(command);
    }

    /**
     * 구매자 회원가입 휴대폰 인증번호를 확인한다.
     * 인증 성공 시 회원가입 API가 소비할 Redis verified 상태를 생성한다.
     */
    @Override
    public CustomerPhoneVerificationConfirmResult confirmSignupPhoneVerification(CustomerPhoneVerificationConfirmCommand command) {
        return customerPhoneVerificationService.confirmSignupCode(command);
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

        SocialLoginTokenResult tokenResult = socialLoginCodeRepository.findByCode(temporaryLoginCode)
                .map(this::readSocialLoginTokenResult)
                .orElseThrow(() -> new CustomerException(CustomerErrorCode.INVALID_LOGIN_CODE));

        socialLoginCodeRepository.delete(temporaryLoginCode);

        return tokenResult;
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

    private RegisterSocialCustomerCommand toRegisterCommand(SocialSignupCommand command, SocialSignupInfo socialSignupInfo) {
        return new RegisterSocialCustomerCommand(
                command.nickname(),
                command.name(),
                command.birthDate(),
                UserGender.valueOf(command.gender()),
                command.phoneNumber(),
                command.email(),
                command.profileImageUrl(),
                command.privacyAgreed(),
                command.marketingAgreed(),
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
