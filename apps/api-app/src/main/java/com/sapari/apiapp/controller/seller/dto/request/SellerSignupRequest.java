package com.sapari.apiapp.controller.seller.dto.request;

import java.time.LocalDate;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.sapari.seller.command.SellerSignupCommand;
import com.sapari.seller.model.SellerBusinessType;

public record SellerSignupRequest(
        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        String email,

        @NotBlank(message = "비밀번호는 필수입니다.")
        @Pattern(
                regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z0-9\\s]).{8,64}$",
                message = "비밀번호는 8~64자이며 영문, 숫자, 특수문자를 각각 1개 이상 포함해야 합니다."
        )
        String password,

        @NotBlank(message = "비밀번호 확인은 필수입니다.")
        String passwordConfirm,

        @NotBlank(message = "닉네임은 필수입니다.")
        @Pattern(
                regexp = "^[가-힣A-Za-z0-9]{2,10}$",
                message = "닉네임은 2~10자의 한글, 영문, 숫자만 사용할 수 있습니다."
        )
        String nickname,

        @NotBlank(message = "대표자명은 필수입니다.")
        @Size(max = 20, message = "대표자명은 20자 이하여야 합니다.")
        String name,

        @NotBlank(message = "전화번호는 필수입니다.")
        @Pattern(regexp = "^0\\d{8,10}$", message = "전화번호 형식이 올바르지 않습니다.")
        String phoneNumber,

        @NotNull(message = "개인정보 수집 및 이용 동의는 필수입니다.")
        @AssertTrue(message = "개인정보 수집 및 이용 동의는 필수입니다.")
        Boolean privacyAgreed,

        Boolean marketingAgreed,

        @NotBlank(message = "상호명은 필수입니다.")
        @Size(max = 20, message = "상호명은 20자 이하여야 합니다.")
        String storeName,

        @NotBlank(message = "사업자번호는 필수입니다.")
        @Pattern(regexp = "^\\d{10}$", message = "사업자번호는 숫자 10자리여야 합니다.")
        String businessNumber,

        @NotNull(message = "개업일자는 필수입니다.")
        LocalDate businessStartDate,

        @NotBlank(message = "사업자 유형은 필수입니다.")
        @Pattern(regexp = "^(INDIVIDUAL|CORPORATE)$", message = "사업자 유형이 올바르지 않습니다.")
        String businessType
) {
    /**
     * 필수 약관 거부/누락은 요청 검증에서 4xx로 차단하고, 선택 약관은 명시적 거부도 command에 전달한다.
     */
    public SellerSignupCommand toCommand() {
        return new SellerSignupCommand(
                email,
                password,
                passwordConfirm,
                nickname,
                name,
                phoneNumber,
                Boolean.TRUE.equals(privacyAgreed),
                Boolean.TRUE.equals(marketingAgreed),
                storeName,
                businessNumber,
                businessStartDate,
                SellerBusinessType.valueOf(businessType)
        );
    }
}
