package com.sapari.user.infrastructure.persistence.entity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.UuidGenerator;
import org.hibernate.annotations.UuidGenerator.Style;

@Entity
@Getter
@Table(name = "withdrawn_user_retentions", schema = "user_schema")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WithdrawnUserRetentionEntity {

    @Id
    @UuidGenerator(style = Style.VERSION_7)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "original_user_id", nullable = false, unique = true)
    private UUID originalUserId;

    @Column(name = "name_masked", length = 20)
    private String nameMasked;

    @Column(name = "email_masked", length = 255)
    private String emailMasked;

    @Column(name = "phone_number_masked", length = 20)
    private String phoneNumberMasked;

    @Column(name = "retention_until", nullable = false)
    private Instant retentionUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "purged_at")
    private Instant purgedAt;

    public static WithdrawnUserRetentionEntity of(
            UUID originalUserId,
            String nameMasked,
            String emailMasked,
            String phoneNumberMasked,
            Instant retentionUntil,
            Instant createdAt,
            Instant purgedAt
    ) {
        WithdrawnUserRetentionEntity entity = new WithdrawnUserRetentionEntity();

        entity.originalUserId = originalUserId;
        entity.nameMasked = nameMasked;
        entity.emailMasked = emailMasked;
        entity.phoneNumberMasked = phoneNumberMasked;
        entity.retentionUntil = retentionUntil;
        entity.createdAt = createdAt;
        entity.purgedAt = purgedAt;

        return entity;
    }
}
