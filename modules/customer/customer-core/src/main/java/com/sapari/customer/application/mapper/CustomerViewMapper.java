package com.sapari.customer.application.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.sapari.customer.application.dto.SocialSignupInfo;
import com.sapari.customer.view.CustomerMeView;
import com.sapari.customer.view.CustomerNicknameUpdateResult;
import com.sapari.customer.view.SocialSignupInfoView;
import com.sapari.user.view.UserView;

@Mapper(componentModel = "spring")
public interface CustomerViewMapper {

    @Mapping(target = "gender", expression = "java(customer.gender() == null ? null : customer.gender().name())")
    @Mapping(target = "role", expression = "java(customer.role().name())")
    @Mapping(target = "status", expression = "java(customer.status().name())")
    @Mapping(target = "grade", expression = "java(customer.grade().name())")
    @Mapping(target = "provider", expression = "java(customer.provider() == null ? null : customer.provider().name())")
    CustomerMeView toMeView(UserView customer);

    @Mapping(target = "email", source = "providerEmail")
    @Mapping(target = "gender", expression = "java(socialSignupInfo.gender() == null ? null : socialSignupInfo.gender().name())")
    SocialSignupInfoView toSocialSignupInfoView(SocialSignupInfo socialSignupInfo);

    default CustomerNicknameUpdateResult toNicknameUpdateResult(UserView customer, String accessToken) {
        return new CustomerNicknameUpdateResult(toMeView(customer), accessToken);
    }
}
