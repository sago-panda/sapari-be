package com.sapari.live.application.port;

import java.time.Duration;

/**
 * Ready 고착 방 만료 정책.
 *
 * @param threshold 이만큼 Ready 에 머문 방이 후보다.
 * @param batchSize 한 회차에 처리할 최대 방 수.
 * @param roundBudget 후보 루프의 <b>시간</b> 상한. 이 잡도 {@code PostCommitMediaCleanup} 을 공유해
 *                    후보당 왕복 수가 방의 화질 수에 비례하므로, 후보 수만으로는 회차가 리스 안에
 *                    있다는 보장이 없다. 자세한 근거는 {@link StaleLiveReconcilePolicy#roundBudget()}.
 */
public record ExpiredReadyReconcilePolicy(Duration threshold, int batchSize, Duration roundBudget) {

    public ExpiredReadyReconcilePolicy {
        if (threshold == null || threshold.isZero() || threshold.isNegative()) {
            throw new IllegalArgumentException("threshold 는 양수여야 합니다: " + threshold);
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize 는 양수여야 합니다: " + batchSize);
        }
        if (roundBudget == null || roundBudget.isZero() || roundBudget.isNegative()) {
            throw new IllegalArgumentException("roundBudget 은 양수여야 합니다: " + roundBudget);
        }
    }
}
