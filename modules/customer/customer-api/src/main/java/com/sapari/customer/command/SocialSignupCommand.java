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

    /** multipart 파일 파트가 실제 바이트를 포함하는지 판단한다. */
    public boolean hasUploadedProfileImage() {
        return profileImageContent != null && profileImageContent.length > 0;
    }
}
