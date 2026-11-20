package com.sapari.customer.port;

import java.util.UUID;

import com.sapari.customer.command.CustomerLogoutCommand;
import com.sapari.customer.command.CustomerEmailVerificationConfirmCommand;
import com.sapari.customer.command.CustomerEmailVerificationSendCommand;
import com.sapari.customer.command.CustomerNicknameUpdateCommand;
import com.sapari.customer.command.CustomerPhoneVerificationConfirmCommand;
import com.sapari.customer.command.CustomerPhoneVerificationSendCommand;
import com.sapari.customer.command.CustomerProfileImageChangeCommand;
import com.sapari.customer.command.SocialSignupCommand;

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

public interface CustomerAuthUseCase {

    /** 서버가 보관한 OAuth 가입 정보와 추가 입력을 결합하고 선택한 프로필 이미지 정책까지 적용해 가입을 완료한다. */
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

    /**
     * 구매자 회원가입 이메일 인증번호를 발송한다.
     * 구현체는 user 인증 정책을 호출하되 CUSTOMER 오류 계약으로 변환한다.
     */
    CustomerEmailVerificationSendResult sendSignupEmailVerification(CustomerEmailVerificationSendCommand command);

    /**
     * 구매자 회원가입 이메일 인증번호를 확인한다.
     * 성공 결과는 화면 상태용이며, 최종 가입 허용은 가입 API의 verified 소비가 기준이다.
     */
    CustomerEmailVerificationConfirmResult confirmSignupEmailVerification(CustomerEmailVerificationConfirmCommand command);

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

    /** 인증된 고객의 프로필 이미지를 교체하고 공개 URL이 반영된 내 정보를 반환한다. */
    CustomerMeView updateProfileImage(CustomerProfileImageChangeCommand command);

    /** 인증된 고객의 프로필 이미지 연결을 제거하고 갱신된 내 정보를 반환한다. */
    CustomerMeView deleteProfileImage(String accessToken);
}
