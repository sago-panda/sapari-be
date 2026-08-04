package com.sapari.live.infrastructure.config;

import jakarta.validation.Valid;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 고아 라이브 정리 정책.
 *
 * <p>미설정은 허용하고(기본값), 잘못된 값은 부팅에서 막는다 — 이 저장소는 {@code application*.yml} 을
 * 추적하지 않아 "설정 없음"이 기본 상태다. 없다고 앱이 안 뜨면 안 되지만, 0 이나 음수 유예로 뜨면
 * 새벽 배치가 정상 리소스를 지운다.
 */
@Validated
@ConfigurationProperties("live.reconcile")
public record LiveReconcileProperties(
        @Valid OrphanMedia orphanMedia
) {
    public LiveReconcileProperties {
        if (orphanMedia == null) {
            orphanMedia = new OrphanMedia(null);
        }
    }

    /**
     * @param grace 이만큼 지난 리소스만 회수한다. 방금 생성돼 아직 DB 에 반영되지 않은 정상 리소스를
     *              지우지 않기 위한 유예이므로 0 이 될 수 없다.
     */
    public record OrphanMedia(
            Duration grace
    ) {
        private static final Duration DEFAULT_GRACE = Duration.ofMinutes(15);

        public OrphanMedia {
            if (grace == null) {
                grace = DEFAULT_GRACE;
            }
            if (grace.isZero() || grace.isNegative()) {
                throw new IllegalArgumentException("live.reconcile.orphan-media.grace 는 양수여야 합니다: " + grace);
            }
        }
    }
}
