package com.sapari.apiapp.controller.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import com.sapari.customer.command.CustomerEmailVerificationSendCommand;
import com.sapari.seller.command.SellerEmailVerificationSendCommand;

/**
 * 회원가입 이메일 인증번호 발송 요청 DTO다.
 * 프론트의 인증 완료 여부는 받지 않고, 이메일만 서버 인증 흐름으로 전달한다.
 */
public record EmailVerificationSendRequest(
        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        String email
) {

    /**
     * 구매자 인증 usecase가 사용할 발송 command로 변환한다.
     */
    public CustomerEmailVerificationSendCommand toCustomerCommand() {
        return new CustomerEmailVerificationSendCommand(email);
    }

    /**
     * 판매자 인증 usecase가 사용할 발송 command로 변환한다.
     */
    public SellerEmailVerificationSendCommand toSellerCommand() {
        return new SellerEmailVerificationSendCommand(email);
    }
}
