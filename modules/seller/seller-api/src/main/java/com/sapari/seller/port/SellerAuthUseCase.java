package com.sapari.seller.port;

import java.util.UUID;

import com.sapari.seller.command.SellerLoginCommand;
import com.sapari.seller.command.SellerLogoutCommand;
import com.sapari.seller.command.SellerNicknameUpdateCommand;
import com.sapari.seller.command.SellerSignupCommand;
import com.sapari.seller.view.SellerLoginResult;
import com.sapari.seller.view.SellerMeView;
import com.sapari.seller.view.SellerNicknameUpdateResult;
import com.sapari.seller.view.SellerSignupResult;
import com.sapari.seller.view.SellerTokenReissueResult;

public interface SellerAuthUseCase {

    SellerSignupResult signup(SellerSignupCommand command);

    boolean isEmailDuplicated(String email);

    boolean isPhoneNumberDuplicated(String phoneNumber);

    boolean isNicknameDuplicated(String nickname);

    boolean isStoreNameDuplicated(String storeName);

    SellerLoginResult login(SellerLoginCommand command);

    SellerTokenReissueResult reissueAccessToken(String refreshToken);

    void logout(SellerLogoutCommand command);

    void requestWithdrawal(String accessToken);

    SellerMeView getMyInfo(UUID userId);

    SellerNicknameUpdateResult updateNickname(SellerNicknameUpdateCommand command);
}
