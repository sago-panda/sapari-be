package com.sapari.customer.command;

import java.time.LocalDate;

/**
 * 소셜 고객 가입 정보와 선택적 직접 업로드 이미지를 application 계층에 전달한다.
 */
public record SocialSignupCommand(
        String phoneNumber,
        String email,
        String nickname,
        String name,
        LocalDate birthDate,
        String gender,
        boolean useSocialProfileImage,
        String profileImageOriginalFilename,
        String profileImageContentType,
        byte[] profileImageContent,
        boolean privacyAgreed,
        boolean marketingAgreed
) {
    /** 호출자가 보유한 원본 배열의 변경이 전달된 이미지에 영향을 주지 않도록 복사한다. */
    public SocialSignupCommand {
        profileImageContent = profileImageContent == null ? null : profileImageContent.clone();
    }

    /** 내부 배열을 노출하지 않으며 이미지 미선택을 나타내는 null은 유지한다. */
    @Override
    public byte[] profileImageContent() {
        return profileImageContent == null ? null : profileImageContent.clone();
    }


    /** multipart 파일 파트가 실제 바이트를 포함하는지 판단한다. */
    public boolean hasUploadedProfileImage() {
        return profileImageContent != null && profileImageContent.length > 0;
    }
}
