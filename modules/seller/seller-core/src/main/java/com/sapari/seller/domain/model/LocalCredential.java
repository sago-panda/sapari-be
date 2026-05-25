package com.sapari.seller.domain.model;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.util.Assert;

public record LocalCredential(
        UUID userId,
        String passwordHash,
        Integer failedLoginCount,
        LocalDateTime lockedAt,
        LocalDateTime lastChangedAt
) {

    public static LocalCredential create(UUID userId, String passwordHash) {
        Assert.notNull(userId, "userId는 필수입니다.");
        Assert.hasText(passwordHash, "passwordHash는 필수입니다.");

        return new LocalCredential(
                userId,
                passwordHash,
                0,
                null,
                LocalDateTime.now()
        );
    }
}
