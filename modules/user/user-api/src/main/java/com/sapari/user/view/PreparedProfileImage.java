package com.sapari.user.view;

/**
 * 파일 검증과 재인코딩을 마쳐 object storage에 저장할 수 있는 프로필 이미지다.
 * userId와 object key는 저장 단계에서 결정해 순수 파일 검증을 회원가입 DB 처리 전에 수행할 수 있게 한다.
 */
public record PreparedProfileImage(
        String normalizedExtension,
        String contentType,
        byte[] content
) {
    /** 정규화된 저장 형식을 검증하고 이미지 바이트의 소유권을 분리한다. */
    public PreparedProfileImage {
        boolean supported = ("png".equals(normalizedExtension) && "image/png".equals(contentType))
                || ("jpg".equals(normalizedExtension) && "image/jpeg".equals(contentType));
        if (!supported || content == null || content.length == 0) {
            throw new IllegalArgumentException("정규화된 이미지 형식과 비어 있지 않은 내용이 필요합니다.");
        }
        content = content.clone();
    }

    /** 검증 이후 호출자가 내부 이미지 바이트를 변경하지 못하도록 복사본을 반환한다. */
    @Override
    public byte[] content() {
        return content.clone();
    }
}
