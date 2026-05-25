package com.sapari.seller.infrastructure.persistence.entity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.util.Assert;

@Entity
@Getter
@Table(name = "local_credentials")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LocalCredentialEntity {

    @Id
    @Column(name = "users_id")
    private UUID userId;

    @Column(nullable = false, length = 255)
    private String passwordHash;

    @Column(nullable = false)
    private Integer failedLoginCount = 0;

    private LocalDateTime lockedAt;

    @Column(nullable = false)
    private LocalDateTime lastChangedAt;

    public static LocalCredentialEntity of(
            UUID userId,
            String passwordHash,
            Integer failedLoginCount,
            LocalDateTime lockedAt,
            LocalDateTime lastChangedAt
    ) {
        Assert.notNull(userId, "userId는 필수입니다.");
        Assert.hasText(passwordHash, "passwordHash는 필수입니다.");
        Assert.notNull(failedLoginCount, "failedLoginCount는 필수입니다.");
        Assert.notNull(lastChangedAt, "lastChangedAt은 필수입니다.");

        LocalCredentialEntity entity = new LocalCredentialEntity();

        entity.userId = userId;
        entity.passwordHash = passwordHash;
        entity.failedLoginCount = failedLoginCount;
        entity.lockedAt = lockedAt;
        entity.lastChangedAt = lastChangedAt;

        return entity;
    }
}
