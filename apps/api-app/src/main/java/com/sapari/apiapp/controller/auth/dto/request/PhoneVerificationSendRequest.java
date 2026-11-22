package com.sapari.apiapp.controller.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import com.sapari.customer.command.CustomerPhoneVerificationSendCommand;

/**
 * 구매자 회원가입 휴대폰 인증번호 발송 요청 DTO다.
 * 프론트의 인증 완료 여부는 받지 않고, 전화번호만 서버 인증 흐름으로 전달한다.
 */
public record PhoneVerificationSendRequest(
        @NotBlank(message = "전화번호는 필수입니다.")
        @Pattern(regexp = "^010\\d{8}$", message = "전화번호는 010으로 시작하는 숫자 11자리여야 합니다.")
        String phoneNumber
) {

    /**
     * 구매자 인증 usecase가 사용할 발송 command로 변환한다.
     */
    public CustomerPhoneVerificationSendCommand toCommand() {
        return new CustomerPhoneVerificationSendCommand(phoneNumber);
    }
}
