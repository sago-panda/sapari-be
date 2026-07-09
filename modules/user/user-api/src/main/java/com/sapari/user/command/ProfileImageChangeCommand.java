package com.sapari.user.command;

import java.util.UUID;

/**
 * 프로필 이미지 변경 요청에서 user-core가 필요한 파일 메타데이터와 바이트다.
 * userId는 HTTP 요청값이 아니라 인증된 사용자 식별자를 호출자가 확정해서 전달한다.
 */
public record ProfileImageChangeCommand(
        UUID userId,
        String originalFilename,
        String contentType,
        byte[] content
) {
}
