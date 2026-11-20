package com.sapari.customer.application.dto;

/**
 * OAuth provider 이미지의 다운로드·검증·재인코딩이 끝난 결과다.
 */
public record SocialProfileImageDownloadResult(
        String normalizedExtension,
        String contentType,
        byte[] content
) {
    /** 호출자가 보유한 원본 배열의 변경이 전달된 이미지에 영향을 주지 않도록 복사한다. */
    public SocialProfileImageDownloadResult {
        content = content == null ? null : content.clone();
    }

    /** 내부 배열을 노출하지 않으며 이미지 미선택을 나타내는 null은 유지한다. */
    @Override
    public byte[] content() {
        return content == null ? null : content.clone();
    }

}
