package com.sapari.customer.port;

import java.util.UUID;

import com.sapari.customer.command.CustomerLogoutCommand;
import com.sapari.customer.command.CustomerNicknameUpdateCommand;
import com.sapari.customer.command.SocialSignupCommand;
import com.sapari.customer.result.CustomerMeResult;
import com.sapari.customer.result.CustomerNicknameUpdateResult;
import com.sapari.customer.result.CustomerTokenReissueResult;
import com.sapari.customer.result.SocialSignupInfoResult;
import com.sapari.customer.result.SocialLoginTokenResult;
import com.sapari.customer.result.SocialSignupResult;

public interface CustomerAuthUseCase {

    SocialSignupResult completeSocialSignup(String signupSid, SocialSignupCommand command);

    SocialSignupInfoResult getSocialSignupInfo(String signupSid);

    SocialLoginTokenResult exchangeTemporaryLoginCode(String temporaryLoginCode);

    CustomerTokenReissueResult reissueAccessToken(String refreshToken);

    void logout(CustomerLogoutCommand command);

    boolean isPhoneNumberDuplicated(String phoneNumber);

    boolean isEmailDuplicated(String email);

    boolean isNicknameDuplicated(String nickname);

    CustomerMeResult getMyInfo(UUID userId);

    CustomerNicknameUpdateResult updateNickname(CustomerNicknameUpdateCommand command);
}
