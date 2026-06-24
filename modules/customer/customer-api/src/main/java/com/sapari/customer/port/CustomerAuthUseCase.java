package com.sapari.customer.port;

import java.util.UUID;

import com.sapari.customer.command.CustomerLogoutCommand;
import com.sapari.customer.command.CustomerNicknameUpdateCommand;
import com.sapari.customer.command.CustomerPhoneVerificationConfirmCommand;
import com.sapari.customer.command.CustomerPhoneVerificationSendCommand;
import com.sapari.customer.command.SocialSignupCommand;

import com.sapari.customer.view.CustomerMeView;
import com.sapari.customer.view.CustomerNicknameUpdateResult;
import com.sapari.customer.view.CustomerPhoneVerificationConfirmResult;
import com.sapari.customer.view.CustomerPhoneVerificationSendResult;
import com.sapari.customer.view.CustomerTokenReissueResult;
import com.sapari.customer.view.SocialSignupInfoView;
import com.sapari.customer.view.SocialLoginTokenResult;
import com.sapari.customer.view.SocialSignupResult;

public interface CustomerAuthUseCase {

    SocialSignupResult completeSocialSignup(String signupSid, SocialSignupCommand command);

    /**
     * 구매자 회원가입 휴대폰 인증번호를 발송한다.
     * 구현체는 재요청 쿨다운과 발송 실패 정책을 적용하고, 발송 성공 시에만 인증 상태를 저장한다.
     */
    CustomerPhoneVerificationSendResult sendSignupPhoneVerification(CustomerPhoneVerificationSendCommand command);

    /**
     * 구매자 회원가입 휴대폰 인증번호를 확인한다.
     * 구현체는 인증번호 불일치 횟수를 관리하고, 성공 시 회원가입 API가 소비할 verified 상태를 저장한다.
     */
    CustomerPhoneVerificationConfirmResult confirmSignupPhoneVerification(CustomerPhoneVerificationConfirmCommand command);

    SocialSignupInfoView getSocialSignupInfo(String signupSid);

    SocialLoginTokenResult exchangeTemporaryLoginCode(String temporaryLoginCode);

    CustomerTokenReissueResult reissueAccessToken(String refreshToken);

    void logout(CustomerLogoutCommand command);

    /**
     * Access Token으로 현재 고객을 식별해 회원탈퇴를 신청하고 모든 세션을 폐기한다.
     */
    void requestWithdrawal(String accessToken);

    boolean isPhoneNumberDuplicated(String phoneNumber);

    boolean isEmailDuplicated(String email);

    boolean isNicknameDuplicated(String nickname);

    CustomerMeView getMyInfo(UUID userId);

    CustomerNicknameUpdateResult updateNickname(CustomerNicknameUpdateCommand command);
}
