package com.sapari.seller.domain.model;

import java.time.Instant;
import java.util.UUID;

import org.springframework.util.Assert;

public record LocalCredential(
        UUID userId,
        String passwordHash,
        Integer failedLoginCount,
        Instant lockedAt,
        Instant lastChangedAt
) {

    public static LocalCredential create(UUID userId, String passwordHash, Instant lastChangedAt) {
        Assert.notNull(userId, "userId는 필수입니다.");
        Assert.hasText(passwordHash, "passwordHash는 필수입니다.");
        Assert.notNull(lastChangedAt, "lastChangedAt은 필수입니다.");

        return new LocalCredential(
                userId,
                passwordHash,
                0,
                null,
                lastChangedAt
        );
    }
}
