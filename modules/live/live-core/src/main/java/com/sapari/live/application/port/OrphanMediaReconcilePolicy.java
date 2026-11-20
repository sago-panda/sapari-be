package com.sapari.live.application.port;

import java.time.Duration;

/**
 * 고아 미디어 회수 정책. {@code @ConfigurationProperties} 를 서비스가 직접 받으면
 * application → infrastructure 의존이 되므로(ArchUnit 금지), 설정은 어댑터에서 이 record 로 바꿔 넘긴다.
 *
 * @param grace 이만큼 지난 리소스만 회수한다 — 생성 직후라 아직 DB 에 없는 정상 리소스를 지우지 않기 위한 유예.
 * <p>개수 상한이 없다 — 정지 규칙은 {@code roundBudget} 하나다. 종류별 batch-size 가 있던 시절에는
 * 같은 방의 ingress 삭제만 상한에 걸려 밀리고 방은 닫히는 조합을 막을 수 없었다(살아남은 ingress 로
 * OBS 가 방을 되살린다). 상한 자체가 회차 비용을 미리 계산할 수 있다는, 성립하지 않는 전제 위에 있었다.
 *
 * @param roundBudget 회차 전체가 쓸 수 있는 <b>시간</b> 상한. 방 하나의 리소스를 전부 처리하므로 방당 왕복 수가
 *                    그 방의 리소스 수에 비례한다 — 개수로는 회차를 리스 안에 묶을 수 없다. 세 잡 중 <b>DB 게이트가 없는 유일한 잡</b>이라 리스를 넘겨
 *                    겹쳐 도는 대가가 가장 크다. 자세한 근거는 {@link StaleLiveReconcilePolicy#roundBudget()}.
 */
public record OrphanMediaReconcilePolicy(Duration grace, Duration roundBudget) {

    public OrphanMediaReconcilePolicy {
        if (grace == null || grace.isZero() || grace.isNegative()) {
            throw new IllegalArgumentException("grace 는 양수여야 합니다: " + grace);
        }
        if (roundBudget == null || roundBudget.isZero() || roundBudget.isNegative()) {
            throw new IllegalArgumentException("roundBudget 은 양수여야 합니다: " + roundBudget);
        }
    }
}
