package com.sapari.apiapp.controller.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import com.sapari.customer.command.CustomerPhoneVerificationConfirmCommand;

/**
 * 구매자 회원가입 휴대폰 인증번호 확인 요청 DTO다.
 * 서버는 전화번호와 code를 기준으로 Redis의 codeHash를 검증하고 verified 상태를 생성한다.
 */
public record PhoneVerificationConfirmRequest(
        @NotBlank(message = "전화번호는 필수입니다.")
        @Pattern(regexp = "^010\\d{8}$", message = "전화번호는 010으로 시작하는 숫자 11자리여야 합니다.")
        String phoneNumber,

        @NotBlank(message = "인증번호는 필수입니다.")
        @Pattern(regexp = "^\\d{6}$", message = "인증번호는 숫자 6자리여야 합니다.")
        String code
) {

    /**
     * 구매자 인증 usecase가 사용할 확인 command로 변환한다.
     */
    public CustomerPhoneVerificationConfirmCommand toCommand() {
        return new CustomerPhoneVerificationConfirmCommand(phoneNumber, code);
    }
}
