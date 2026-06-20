package com.sapari.user.infrastructure.persistence.entity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.sapari.storage.db.entity.BaseUuidEntity;

@Entity
@Getter
@Table(name = "withdrawn_user_retentions", schema = "user_schema")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WithdrawnUserRetentionEntity extends BaseUuidEntity {

    @Column(nullable = false, unique = true)
    private UUID originalUserId;

    @Column(length = 20)
    private String nameMasked;

    @Column(length = 255)
    private String emailMasked;

    @Column(length = 20)
    private String phoneNumberMasked;

    @Column(nullable = false)
    private Instant retentionUntil;

    private Instant purgedAt;

    public static WithdrawnUserRetentionEntity of(
            UUID originalUserId,
            String nameMasked,
            String emailMasked,
            String phoneNumberMasked,
            Instant retentionUntil,
            Instant purgedAt
    ) {
        WithdrawnUserRetentionEntity entity = new WithdrawnUserRetentionEntity();

        entity.originalUserId = originalUserId;
        entity.nameMasked = nameMasked;
        entity.emailMasked = emailMasked;
        entity.phoneNumberMasked = phoneNumberMasked;
        entity.retentionUntil = retentionUntil;
        entity.purgedAt = purgedAt;

        return entity;
    }
}
