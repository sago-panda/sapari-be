package com.sapari.customer.application.dto;

/**
 * OAuth provider 이미지의 다운로드·검증·재인코딩이 끝난 결과다.
 */
public record SocialProfileImageDownloadResult(
        String normalizedExtension,
        String contentType,
        byte[] content
) {
}
