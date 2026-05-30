package com.sapari.member.port;

import java.util.UUID;

import com.sapari.member.command.MemberLogoutCommand;
import com.sapari.member.command.MemberNicknameUpdateCommand;
import com.sapari.member.command.SocialSignupCommand;
import com.sapari.member.result.MemberMeResult;
import com.sapari.member.result.MemberTokenReissueResult;
import com.sapari.member.result.SocialSignupInfoResult;
import com.sapari.member.result.SocialLoginTokenResult;
import com.sapari.member.result.SocialSignupResult;

public interface MemberAuthUseCase {

    SocialSignupResult completeSocialSignup(String signupSid, SocialSignupCommand command);

    SocialSignupInfoResult getSocialSignupInfo(String signupSid);

    SocialLoginTokenResult exchangeTemporaryLoginCode(String temporaryLoginCode);

    MemberTokenReissueResult reissueAccessToken(String refreshToken);

    void logout(MemberLogoutCommand command);

    boolean isPhoneNumberDuplicated(String phoneNumber);

    boolean isEmailDuplicated(String email);

    boolean isNicknameDuplicated(String nickname);

    boolean isMyNicknameDuplicated(UUID userId, String nickname);

    MemberMeResult getMyInfo(UUID userId);

    MemberMeResult updateNickname(MemberNicknameUpdateCommand command);
}
