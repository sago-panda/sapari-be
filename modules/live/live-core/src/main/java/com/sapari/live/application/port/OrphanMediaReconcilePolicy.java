package com.sapari.live.application.port;

import java.time.Duration;

/**
 * 고아 미디어 회수 정책. {@code @ConfigurationProperties} 를 서비스가 직접 받으면
 * application → infrastructure 의존이 되므로(ArchUnit 금지), 설정은 어댑터에서 이 record 로 바꿔 넘긴다.
 *
 * @param grace 이만큼 지난 리소스만 회수한다 — 생성 직후라 아직 DB 에 없는 정상 리소스를 지우지 않기 위한 유예.
 */
public record OrphanMediaReconcilePolicy(Duration grace) {

    public OrphanMediaReconcilePolicy {
        if (grace == null || grace.isZero() || grace.isNegative()) {
            throw new IllegalArgumentException("grace 는 양수여야 합니다: " + grace);
        }
    }
}
