package com.sapari.storage.db.entity;

import lombok.Getter;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;

import org.springframework.data.annotation.LastModifiedDate;

/**
 * bigint PK(BaseLongEntity = IDENTITY) + created_at 에 updated_at 을 더한 공통 베이스.
 * (UuidTimeEntity 가 BaseUuidEntity 를 확장하는 것과 동일한 구조)
 */
@Getter
@MappedSuperclass
public abstract class LongTimeEntity extends BaseLongEntity {

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
