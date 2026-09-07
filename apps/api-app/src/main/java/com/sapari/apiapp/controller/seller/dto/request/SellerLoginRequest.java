package com.sapari.apiapp.controller.seller.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import com.sapari.seller.command.SellerLoginCommand;

public record SellerLoginRequest(
        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        String email,

        @NotBlank(message = "비밀번호는 필수입니다.")
        String password
) {
    /** 로그·디버그 출력에 비밀번호와 개인정보가 포함되지 않도록 고정 문자열만 반환한다. */
    @Override
    public String toString() {
        return "SellerLoginRequest[REDACTED]";
    }


    public SellerLoginCommand toCommand() {
        return new SellerLoginCommand(email, password);
    }
}
