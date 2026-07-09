package com.sapari.storage.object.command;

import java.util.Objects;

/**
 * object storage에 저장할 객체 바이트와 메타데이터다.
 * bucket은 공통 storage 설정이 소유하고, 도메인 모듈은 저장할 object key만 넘긴다.
 */
public record ObjectPutCommand(
        String key,
        String contentType,
        byte[] content
) {
    public ObjectPutCommand {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("contentType must not be blank");
        }
        Objects.requireNonNull(content, "content must not be null");
    }
}
