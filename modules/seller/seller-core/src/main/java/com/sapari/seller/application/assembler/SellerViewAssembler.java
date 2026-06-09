package com.sapari.seller.application.assembler;

import org.springframework.stereotype.Component;

import com.sapari.seller.domain.model.SellerProfile;
import com.sapari.seller.view.SellerMeView;
import com.sapari.seller.view.SellerNicknameUpdateResult;
import com.sapari.user.view.UserView;

@Component
public class SellerViewAssembler {

    public SellerMeView toMeView(UserView seller, SellerProfile sellerProfile) {
        return new SellerMeView(
                seller.userId(),
                seller.nickname(),
                seller.name(),
                seller.birthDate(),
                seller.phoneNumber(),
                seller.profileImageKey(),
                seller.email(),
                seller.role().name(),
                seller.status().name(),
                seller.grade().name(),
                seller.pointBalance(),
                seller.marketingAgreed(),
                sellerProfile.storeName(),
                sellerProfile.businessNumber(),
                sellerProfile.businessType().name(),
                sellerProfile.status().name(),
                sellerProfile.rejectionReason(),
                sellerProfile.approvedAt()
        );
    }

    public SellerNicknameUpdateResult toNicknameUpdateResult(
            UserView seller,
            SellerProfile sellerProfile,
            String accessToken
    ) {
        return new SellerNicknameUpdateResult(toMeView(seller, sellerProfile), accessToken);
    }
}
