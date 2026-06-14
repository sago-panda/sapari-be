package com.sapari.customer.port;

import java.util.UUID;

import com.sapari.customer.command.CustomerLogoutCommand;
import com.sapari.customer.command.CustomerNicknameUpdateCommand;
import com.sapari.customer.command.SocialSignupCommand;

import com.sapari.customer.view.CustomerMeView;
import com.sapari.customer.view.CustomerNicknameUpdateResult;
import com.sapari.customer.view.CustomerTokenReissueResult;
import com.sapari.customer.view.SocialSignupInfoView;
import com.sapari.customer.view.SocialLoginTokenResult;
import com.sapari.customer.view.SocialSignupResult;

public interface CustomerAuthUseCase {

    SocialSignupResult completeSocialSignup(String signupSid, SocialSignupCommand command);

    SocialSignupInfoView getSocialSignupInfo(String signupSid);

    SocialLoginTokenResult exchangeTemporaryLoginCode(String temporaryLoginCode);

    CustomerTokenReissueResult reissueAccessToken(String refreshToken);

    void logout(CustomerLogoutCommand command);

    void requestWithdrawal(String accessToken);

    boolean isPhoneNumberDuplicated(String phoneNumber);

    boolean isEmailDuplicated(String email);

    boolean isNicknameDuplicated(String nickname);

    CustomerMeView getMyInfo(UUID userId);

    CustomerNicknameUpdateResult updateNickname(CustomerNicknameUpdateCommand command);
}
