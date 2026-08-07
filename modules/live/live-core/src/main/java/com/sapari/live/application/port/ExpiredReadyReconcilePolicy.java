package com.sapari.live.application.port;

import java.time.Duration;

public record ExpiredReadyReconcilePolicy(Duration threshold, int batchSize) {

    public ExpiredReadyReconcilePolicy {
        if (threshold == null || threshold.isZero() || threshold.isNegative()) {
            throw new IllegalArgumentException("threshold 는 양수여야 합니다: " + threshold);
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize 는 양수여야 합니다: " + batchSize);
        }
    }
}
