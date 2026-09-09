package com.sapari.live.application.port;

import java.time.Duration;

/**
 * 방치된 Live 방 정리 정책.
 *
 * @param threshold Live 로 전이한 지 이만큼 지난 방만 후보로 본다. 활성 egress 확인이 실제 판정이고,
 *                  이 값은 일시적 조회 실패·egress 재시작을 흡수하는 완충이라 넉넉해야 한다.
 * @param batchSize 한 회차에 처리할 최대 방 수. 남은 건 다음 회차가 가져간다.
 * @param roundBudget 회차가 후보 루프에 쓸 수 있는 <b>시간</b> 상한. batchSize 와 함께 두 번째 정지
 *                    조건이다 — 후보 수가 아니라 LiveKit 응답 속도가 회차를 늘리는 경우를 막는다.
 *                    <p>왜 필요한가: 이 잡의 후보당 왕복은 유한하지 않다. 종료 정리
 *                    ({@code PostCommitMediaCleanup})가 {@code stopHlsEgress} 로 방의 egress 를
 *                    <b>전부</b> 끊는데, 그 수는 방의 화질 수라 설정으로 늘어난다. 그래서
 *                    {@code lock-at-most-for} 를 "후보당 8왕복" 같은 고정 추측으로 계산해 두면
 *                    전제가 깨지는 순간 회차가 리스를 넘고, 그 뒤로는 락이 만료된 채 돌면서 다음
 *                    tick 의 다른 레플리카가 같은 회차를 겹쳐 돈다.
 *                    <p>대신 리스에서 <b>파생</b>해 회차를 리스 안에 가둔다. 마감은 후보 <b>사이</b>에서만
 *                    보므로(진행 중인 HTTP 호출은 끊지 않는다) 보장은 "리스 ≥ 예산 + 후보 1건의 fan-out"
 *                    이다. 무한이 아니라 방 하나의 egress 수로 줄이는 게 이 값의 역할이다.
 */
public record StaleLiveReconcilePolicy(Duration threshold, int batchSize, Duration roundBudget) {

    public StaleLiveReconcilePolicy {
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
