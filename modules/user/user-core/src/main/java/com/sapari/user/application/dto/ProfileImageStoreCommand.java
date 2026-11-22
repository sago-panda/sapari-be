package com.sapari.user.application.dto;

import java.util.UUID;

/**
 * 저장 어댑터로 전달되는 프로필 이미지 원본 바이트와 호출자가 확정한 메타데이터다.
 * 호출자는 확장자, Content-Type, 바이트 검증을 완료한 뒤 이 command를 만들어야 하며,
 * 저장 계층은 이 값을 바탕으로 object key를 생성하고 파일 메타데이터와 함께 저장소에 업로드한다.
 */
public record ProfileImageStoreCommand(
        UUID userId,
        String normalizedExtension,
        String contentType,
        byte[] content
) {
}
