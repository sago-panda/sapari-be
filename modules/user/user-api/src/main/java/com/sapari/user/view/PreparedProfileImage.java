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
}
