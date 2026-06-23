package com.sapari.storage.db.entity;

import lombok.Getter;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;

import org.springframework.data.annotation.LastModifiedDate;

@Getter
@MappedSuperclass
public abstract class UuidTimeEntity extends BaseUuidEntity{

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
