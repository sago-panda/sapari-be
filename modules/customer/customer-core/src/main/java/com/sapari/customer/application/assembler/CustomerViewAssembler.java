package com.sapari.customer.application.assembler;

import org.springframework.stereotype.Component;

import com.sapari.customer.view.CustomerMeView;
import com.sapari.customer.view.CustomerNicknameUpdateResult;
import com.sapari.user.view.UserView;

@Component
public class CustomerViewAssembler {

    public CustomerMeView toMeView(UserView customer) {
        return new CustomerMeView(
                customer.userId(),
                customer.nickname(),
                customer.name(),
                customer.birthDate(),
                customer.gender() == null ? null : customer.gender().name(),
                customer.phoneNumber(),
                customer.profileImageKey(),
                customer.email(),
                customer.role().name(),
                customer.status().name(),
                customer.grade().name(),
                customer.pointBalance(),
                customer.marketingAgreed(),
                customer.provider() == null ? null : customer.provider().name()
        );
    }

    public CustomerNicknameUpdateResult toNicknameUpdateResult(UserView customer, String accessToken) {
        return new CustomerNicknameUpdateResult(toMeView(customer), accessToken);
    }
}
