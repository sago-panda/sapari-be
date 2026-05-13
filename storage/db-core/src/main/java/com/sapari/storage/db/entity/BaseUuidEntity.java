package com.sapari.storage.db.entity;

import lombok.Getter;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;

import org.hibernate.annotations.UuidGenerator;
import org.hibernate.annotations.UuidGenerator.Style;

@Getter
@MappedSuperclass
public abstract class BaseUuidEntity extends BaseTimeEntity{

    @Id
    @UuidGenerator(style = Style.VERSION_7)
    @Column(updatable = false, nullable = false)
    private UUID id;
}