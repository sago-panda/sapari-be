package com.sapari.apiapp.controller.customer.dto.request;

import java.time.LocalDate;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.sapari.apiapp.controller.support.multipart.ProfileImageMultipartFileReader;
import com.sapari.customer.command.SocialSignupCommand;

/**
 * 소셜 고객 가입의 추가 정보와 프로필 이미지 선택 정책을 전달하는 multipart JSON 파트다.
 */
public record SocialSignupRequest(
        @NotBlank(message = "전화번호는 필수입니다.")
        @Pattern(regexp = "^010\\d{8}$", message = "전화번호는 010으로 시작하는 숫자 11자리여야 합니다.")
        String phoneNumber,

        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        String email,

        @NotBlank(message = "닉네임은 필수입니다.")
        @Pattern(
                regexp = "^[가-힣A-Za-z0-9]{2,10}$",
                message = "닉네임은 2~10자의 한글, 영문, 숫자만 사용할 수 있습니다."
        )
        String nickname,

        @NotBlank(message = "이름은 필수입니다.")
        @Size(max = 20, message = "이름은 20자 이하여야 합니다.")
        String name,

        @NotNull(message = "생년월일은 필수입니다.")
        LocalDate birthDate,

        @NotNull(message = "성별은 필수입니다.")
        @Pattern(regexp = "MALE|FEMALE", message = "성별 형식이 올바르지 않습니다.")
        String gender,

        Boolean useSocialProfileImage,

        @NotNull(message = "개인정보 수집 및 이용 동의는 필수입니다.")
        @AssertTrue(message = "개인정보 수집 및 이용 동의는 필수입니다.")
        Boolean privacyAgreed,

        Boolean marketingAgreed
) {

    /**
     * 필수 약관 거부/누락은 요청 검증에서 4xx로 차단하고, 선택 약관과 소셜 이미지 사용 여부는 명시적 true만 true로 정규화한다.
     */
    public SocialSignupCommand toCommand(ProfileImageMultipartFileReader.ProfileImageFile profileImageFile) {
        return new SocialSignupCommand(
                phoneNumber,
                email,
                nickname,
                name,
                birthDate,
                gender,
                Boolean.TRUE.equals(useSocialProfileImage),
                profileImageFile == null ? null : profileImageFile.originalFilename(),
                profileImageFile == null ? null : profileImageFile.contentType(),
                profileImageFile == null ? null : profileImageFile.content(),
                Boolean.TRUE.equals(privacyAgreed),
                Boolean.TRUE.equals(marketingAgreed)
        );
    }
}
