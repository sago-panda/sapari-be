package com.sapari.user.infrastructure.persistence.entity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import com.sapari.storage.db.entity.UuidTimeEntity;
import com.sapari.user.model.TermsType;

@Entity
@Getter
@Table(name = "terms", schema = "user_schema")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TermsEntity extends UuidTimeEntity {

    @Column(nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private TermsType type;

    @Column(nullable = false, length = 30)
    private String version;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false)
    private boolean required;

    @Column(nullable = false, length = 500)
    private String contentUrl;

    @Column(nullable = false, length = 20)
    private String contentFormat;

    @Column(nullable = false)
    private Instant effectiveFrom;

    @Column(nullable = false)
    private boolean active = true;

    public static TermsEntity of(
            TermsType type,
            String version,
            String title,
            boolean required,
            String contentUrl,
            String contentFormat,
            Instant effectiveFrom,
            boolean active
    ) {
        TermsEntity entity = new TermsEntity();
        entity.type = type;
        entity.version = version;
        entity.title = title;
        entity.required = required;
        entity.contentUrl = contentUrl;
        entity.contentFormat = contentFormat;
        entity.effectiveFrom = effectiveFrom;
        entity.active = active;
        return entity;
    }
}
