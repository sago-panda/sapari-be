package com.sapari.apiapp.controller.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import com.sapari.customer.command.CustomerEmailVerificationConfirmCommand;
import com.sapari.seller.command.SellerEmailVerificationConfirmCommand;

/**
 * 회원가입 이메일 인증번호 확인 요청 DTO다.
 * 서버는 이메일과 code를 기준으로 Redis의 codeHash를 검증하고 verified 상태를 생성한다.
 */
public record EmailVerificationConfirmRequest(
        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        String email,

        @NotBlank(message = "인증번호는 필수입니다.")
        @Pattern(regexp = "^\\d{6}$", message = "인증번호는 숫자 6자리여야 합니다.")
        String code
) {

    /**
     * 구매자 인증 usecase가 사용할 확인 command로 변환한다.
     */
    public CustomerEmailVerificationConfirmCommand toCustomerCommand() {
        return new CustomerEmailVerificationConfirmCommand(email, code);
    }

    /**
     * 판매자 인증 usecase가 사용할 확인 command로 변환한다.
     */
    public SellerEmailVerificationConfirmCommand toSellerCommand() {
        return new SellerEmailVerificationConfirmCommand(email, code);
    }
}
