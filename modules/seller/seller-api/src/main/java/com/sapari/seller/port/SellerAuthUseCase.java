package com.sapari.seller.port;

import java.util.UUID;

import com.sapari.seller.command.SellerLoginCommand;
import com.sapari.seller.command.SellerLogoutCommand;
import com.sapari.seller.command.SellerNicknameUpdateCommand;
import com.sapari.seller.command.SellerSignupCommand;
import com.sapari.seller.result.SellerLoginResult;
import com.sapari.seller.result.SellerMeResult;
import com.sapari.seller.result.SellerNicknameUpdateResult;
import com.sapari.seller.result.SellerSignupResult;
import com.sapari.seller.result.SellerTokenReissueResult;

public interface SellerAuthUseCase {

    SellerSignupResult signup(SellerSignupCommand command);

    boolean isEmailDuplicated(String email);

    boolean isPhoneNumberDuplicated(String phoneNumber);

    boolean isNicknameDuplicated(String nickname);

    boolean isMyNicknameDuplicated(UUID userId, String nickname);

    SellerLoginResult login(SellerLoginCommand command);

    SellerTokenReissueResult reissueAccessToken(String refreshToken);

    void logout(SellerLogoutCommand command);

    SellerMeResult getMyInfo(UUID userId);

    SellerNicknameUpdateResult updateNickname(SellerNicknameUpdateCommand command);
}
