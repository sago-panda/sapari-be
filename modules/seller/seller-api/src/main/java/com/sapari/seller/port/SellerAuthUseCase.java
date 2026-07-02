package com.sapari.seller.port;

import java.util.UUID;

import com.sapari.seller.command.SellerLoginCommand;
import com.sapari.seller.command.SellerEmailVerificationConfirmCommand;
import com.sapari.seller.command.SellerEmailVerificationSendCommand;
import com.sapari.seller.command.SellerLogoutCommand;
import com.sapari.seller.command.SellerNicknameUpdateCommand;
import com.sapari.seller.command.SellerSignupCommand;
import com.sapari.seller.view.SellerLoginResult;
import com.sapari.seller.view.SellerEmailVerificationConfirmResult;
import com.sapari.seller.view.SellerEmailVerificationSendResult;
import com.sapari.seller.view.SellerMeView;
import com.sapari.seller.view.SellerNicknameUpdateResult;
import com.sapari.seller.view.SellerSignupResult;
import com.sapari.seller.view.SellerTokenReissueResult;

public interface SellerAuthUseCase {

    SellerSignupResult signup(SellerSignupCommand command);

    /**
     * 판매자 회원가입 이메일 인증번호를 발송한다.
     * 판매자 전화번호는 SMS 인증 대상이 아니므로 판매자 가입 소유 검증은 이메일 인증을 기준으로 한다.
     */
    SellerEmailVerificationSendResult sendSignupEmailVerification(SellerEmailVerificationSendCommand command);

    /**
     * 판매자 회원가입 이메일 인증번호를 확인한다.
     * 성공 결과는 화면 상태용이며, 최종 가입 허용은 가입 API의 verified 소비가 기준이다.
     */
    SellerEmailVerificationConfirmResult confirmSignupEmailVerification(SellerEmailVerificationConfirmCommand command);

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
