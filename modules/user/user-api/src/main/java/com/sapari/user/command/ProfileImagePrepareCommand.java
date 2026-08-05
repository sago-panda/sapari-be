package com.sapari.user.command;

/**
 * 회원 식별자와 무관하게 프로필 이미지 파일을 검증·재인코딩하기 위한 입력이다.
 */
public record ProfileImagePrepareCommand(
        String originalFilename,
        String contentType,
        byte[] content
) {
}
