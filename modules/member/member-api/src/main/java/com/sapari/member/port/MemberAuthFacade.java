package com.sapari.member.port;

import java.util.UUID;

import com.sapari.member.command.MemberLogoutCommand;
import com.sapari.member.command.MemberMeUpdateCommand;
import com.sapari.member.command.SocialSignupCommand;
import com.sapari.member.result.MemberMeResult;
import com.sapari.member.result.MemberTokenReissueResult;
import com.sapari.member.result.SocialLoginTokenResult;
import com.sapari.member.result.SocialSignupResult;

public interface MemberAuthFacade {

    SocialSignupResult completeSocialSignup(String signupSid, SocialSignupCommand command);

    SocialLoginTokenResult exchangeTemporaryLoginCode(String temporaryLoginCode);

    MemberTokenReissueResult reissueAccessToken(String refreshToken);

    void logout(MemberLogoutCommand command);

    boolean isPhoneNumberDuplicated(String phoneNumber);

    boolean isEmailDuplicated(String email);

    MemberMeResult getMyInfo(UUID userId);

    MemberMeResult updateMyInfo(MemberMeUpdateCommand command);
}
