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
        @Valid OrphanMedia orphanMedia,
        @Valid EndStaleLive endStaleLive,
        @Valid ExpireReady expireReady,
        Integer batchSize
) {
    private static final int DEFAULT_BATCH_SIZE = 100;

    public LiveReconcileProperties {
        if (orphanMedia == null) {
            orphanMedia = new OrphanMedia(null);
        }
        if (endStaleLive == null) {
            endStaleLive = new EndStaleLive(null);
        }
        if(expireReady == null){
            expireReady = new ExpireReady(null);
        }
        if (batchSize == null) {
            batchSize = DEFAULT_BATCH_SIZE;
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("live.reconcile.batch-size 는 양수여야 합니다: " + batchSize);
        }
    }

    /**
     * @param threshold Live 로 전이한 지 이만큼 지난 방만 후보로 본다. 실제 종료 판정은 활성 egress 유무이고,
     *                  이 값은 일시적 조회 실패를 흡수하는 완충이라 짧게 잡을 이유가 없다.
     */
    public record EndStaleLive(
            Duration threshold
    ) {
        private static final Duration DEFAULT_THRESHOLD = Duration.ofMinutes(60);

        public EndStaleLive {
            if (threshold == null) {
                threshold = DEFAULT_THRESHOLD;
            }
            if (threshold.isZero() || threshold.isNegative()) {
                throw new IllegalArgumentException(
                        "live.reconcile.end-stale-live.threshold 는 양수여야 합니다: " + threshold);
            }
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

    /**
     * @param threshold 이만큼 Ready 에 머문 방은 만료시킨다. EndStaleLive 와 달리 이 시간이 곧 판정이다
     */
    public record ExpireReady(
        Duration threshold
    ){
        private static final Duration DEFAULT_THRESHOLD = Duration.ofMinutes(60);

        public ExpireReady {
            if(threshold == null){
                threshold = DEFAULT_THRESHOLD;
            }
            if(threshold.isZero() || threshold.isNegative()){
                throw new IllegalArgumentException("live.reconcile.expire-ready.threshold 는 양수여야 합니다: " + threshold);
            }
        }
    }
}
