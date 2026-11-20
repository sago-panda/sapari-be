package com.sapari.storage.object.result;

/**
 * object storage 저장이 완료된 객체 식별자와 저장 메타데이터다.
 */
public record StoredObject(
        String key,
        String contentType,
        long contentLength
) {
}
