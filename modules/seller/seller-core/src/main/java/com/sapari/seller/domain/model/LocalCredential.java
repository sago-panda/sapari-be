package com.sapari.seller.domain.model;

import java.time.Duration;
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

    public boolean isLocked(Instant now, Duration lockDuration) {
        Assert.notNull(now, "now는 필수입니다.");
        Assert.notNull(lockDuration, "lockDuration은 필수입니다.");

        // lockedAt부터 잠금 유지 시간이 지나기 전까지만 로그인 잠금 상태로 본다.
        return lockedAt != null && now.isBefore(lockedAt.plus(lockDuration));
    }

    public LocalCredential recordLoginFailure(Instant now, int lockThreshold) {
        Assert.notNull(now, "now는 필수입니다.");
        Assert.isTrue(lockThreshold > 0, "lockThreshold는 1 이상이어야 합니다.");

        int nextFailedLoginCount = failedLoginCount + 1;
        // 연속 실패 횟수가 임계치에 도달한 시각을 잠금 시작 시각으로 저장한다.
        Instant nextLockedAt = nextFailedLoginCount >= lockThreshold ? now : null;

        return new LocalCredential(userId, passwordHash, nextFailedLoginCount, nextLockedAt, lastChangedAt);
    }

    public LocalCredential resetLoginFailures() {
        return new LocalCredential(userId, passwordHash, 0, null, lastChangedAt);
    }

    public boolean hasLoginFailureHistory() {
        return failedLoginCount > 0 || lockedAt != null;
    }
}
