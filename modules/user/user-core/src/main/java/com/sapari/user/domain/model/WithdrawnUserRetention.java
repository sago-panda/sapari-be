package com.sapari.user.domain.model;

import java.time.Instant;
import java.util.UUID;

import org.springframework.util.Assert;

public record WithdrawnUserRetention(
        UUID withdrawnUserRetentionId,
        UUID originalUserId,
        String nameMasked,
        String emailMasked,
        String phoneNumberMasked,
        Instant retentionUntil,
        Instant createdAt,
        Instant purgedAt
) {

    public static WithdrawnUserRetention create(
            UUID originalUserId,
            String nameMasked,
            String emailMasked,
            String phoneNumberMasked,
            Instant retentionUntil,
            Instant createdAt
    ) {
        Assert.notNull(originalUserId, "originalUserId는 필수입니다.");
        Assert.notNull(retentionUntil, "retentionUntil은 필수입니다.");
        Assert.notNull(createdAt, "createdAt은 필수입니다.");

        return new WithdrawnUserRetention(
                null,
                originalUserId,
                nameMasked,
                emailMasked,
                phoneNumberMasked,
                retentionUntil,
                createdAt,
                null
        );
    }
}
