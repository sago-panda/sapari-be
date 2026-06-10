package com.sapari.seller.application.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import com.sapari.seller.domain.model.SellerProfile;
import com.sapari.seller.view.SellerMeView;
import com.sapari.seller.view.SellerNicknameUpdateResult;
import com.sapari.user.view.UserView;

@Mapper(
        componentModel = "spring",
        unmappedTargetPolicy = ReportingPolicy.ERROR
)
public interface SellerViewMapper {

    @Mapping(target = "userId", source = "seller.userId")
    @Mapping(target = "nickname", source = "seller.nickname")
    @Mapping(target = "name", source = "seller.name")
    @Mapping(target = "birthDate", source = "seller.birthDate")
    @Mapping(target = "phoneNumber", source = "seller.phoneNumber")
    @Mapping(target = "profileImageKey", source = "seller.profileImageKey")
    @Mapping(target = "email", source = "seller.email")
    @Mapping(target = "role", expression = "java(seller.role().name())")
    @Mapping(target = "status", expression = "java(seller.status().name())")
    @Mapping(target = "grade", expression = "java(seller.grade().name())")
    @Mapping(target = "pointBalance", source = "seller.pointBalance")
    @Mapping(target = "marketingAgreed", source = "seller.marketingAgreed")
    @Mapping(target = "storeName", source = "sellerProfile.storeName")
    @Mapping(target = "businessNumber", source = "sellerProfile.businessNumber")
    @Mapping(target = "businessType", source = "sellerProfile.businessType")
    @Mapping(target = "approvalStatus", source = "sellerProfile.status")
    @Mapping(target = "rejectionReason", source = "sellerProfile.rejectionReason")
    @Mapping(target = "approvedAt", source = "sellerProfile.approvedAt")
    SellerMeView toMeView(UserView seller, SellerProfile sellerProfile);

    default SellerNicknameUpdateResult toNicknameUpdateResult(
            UserView seller,
            SellerProfile sellerProfile,
            String accessToken
    ) {
        return new SellerNicknameUpdateResult(toMeView(seller, sellerProfile), accessToken);
    }
}
