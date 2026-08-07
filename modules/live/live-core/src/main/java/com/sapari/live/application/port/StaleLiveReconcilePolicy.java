package com.sapari.live.application.port;

import java.time.Duration;

/**
 * 방치된 Live 방 정리 정책.
 *
 * @param threshold Live 로 전이한 지 이만큼 지난 방만 후보로 본다. 활성 egress 확인이 실제 판정이고,
 *                  이 값은 일시적 조회 실패·egress 재시작을 흡수하는 완충이라 넉넉해야 한다.
 * @param batchSize 한 회차에 처리할 최대 방 수. 남은 건 다음 회차가 가져간다.
 */
public record StaleLiveReconcilePolicy(Duration threshold, int batchSize) {

    public StaleLiveReconcilePolicy {
        if (threshold == null || threshold.isZero() || threshold.isNegative()) {
            throw new IllegalArgumentException("threshold 는 양수여야 합니다: " + threshold);
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize 는 양수여야 합니다: " + batchSize);
        }
    }
}
