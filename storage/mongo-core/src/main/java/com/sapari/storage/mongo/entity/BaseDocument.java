package com.sapari.storage.mongo.entity;

import java.time.Instant;

import org.springframework.data.annotation.Id;

import lombok.Getter;

/**
 * MongoDB Document 공통 베이스.
 *
 * id, createdAt 공통 필드만 제공한다. createdAt은 도메인이 발신 시점 스냅샷으로
 * 직접 채우므로(TimeProvider 기반) 감사(@CreatedDate) 자동 채움을 쓰지 않는다.
 */
@Getter
public abstract class BaseDocument {

    @Id
    private String id;

    private Instant createdAt;

    protected BaseDocument() {
    }

    protected BaseDocument(String id, Instant createdAt) {
        this.id = id;
        this.createdAt = createdAt;
    }
}
