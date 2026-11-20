package com.sapari.storage.object.exception;

import java.util.Objects;

/**
 * S3-compatible object storage 작업 중 발생한 예상 가능한 외부 저장소 실패다.
 * 도메인 모듈은 이 예외를 각 도메인의 실패 계약으로 변환한다.
 */
public class ObjectStorageException extends RuntimeException {

    private final ObjectStorageOperation operation;
    private final String key;

    public ObjectStorageException(
            ObjectStorageOperation operation,
            String key,
            Throwable cause
    ) {
        super(cause);
        this.operation = Objects.requireNonNull(operation, "operation must not be null");
        this.key = Objects.requireNonNull(key, "key must not be null");
    }

    public ObjectStorageOperation operation() {
        return operation;
    }

    public String key() {
        return key;
    }
}
