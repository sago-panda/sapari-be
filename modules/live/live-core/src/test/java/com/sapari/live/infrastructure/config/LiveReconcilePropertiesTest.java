package com.sapari.live.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LiveReconcilePropertiesTest {

    @Test
    void jobSpecificBatchSizesDefaultToTen() {
        LiveReconcileProperties properties = new LiveReconcileProperties(null, null, null);

        assertThat(properties.endStaleLive().batchSize()).isEqualTo(10);
        assertThat(properties.expireReady().batchSize()).isEqualTo(20);
    }

    @Test
    void jobSpecificBatchSizesMustBePositive() {
        assertThatThrownBy(() -> new LiveReconcileProperties.EndStaleLive(Duration.ofHours(1), 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LiveReconcileProperties.ExpireReady(Duration.ofHours(1), -1, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void leaseMustBePositive() {
        assertThatThrownBy(() -> new LiveReconcileProperties.EndStaleLive(
                Duration.ofHours(1), 10, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LiveReconcileProperties.ExpireReady(
                Duration.ofHours(1), 20, Duration.ofMinutes(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LiveReconcileProperties.OrphanMedia(Duration.ofMinutes(15), Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 회차 예산은 리스에서 파생된다 — 예산이 리스보다 길면 회차를 리스 안에 가두려던 장치가
     * 아무것도 가두지 않는다. 그 부등식을 여기서 못박는다.
     */
    @Test
    void roundBudgetStaysStrictlyInsideItsOwnLease() {
        LiveReconcileProperties.EndStaleLive endStale =
                new LiveReconcileProperties.EndStaleLive(null, null, Duration.ofMinutes(25));
        LiveReconcileProperties.ExpireReady expireReady =
                new LiveReconcileProperties.ExpireReady(null, null, Duration.ofMinutes(50));

        assertThat(endStale.roundBudget()).isEqualTo(Duration.ofMinutes(20));
        assertThat(expireReady.roundBudget()).isEqualTo(Duration.ofMinutes(40));
        assertThat(endStale.roundBudget()).isLessThan(endStale.lockAtMostFor());
        assertThat(expireReady.roundBudget()).isLessThan(expireReady.lockAtMostFor());
        LiveReconcileProperties.OrphanMedia orphanMedia =
                new LiveReconcileProperties.OrphanMedia(null, Duration.ofMinutes(20));
        assertThat(orphanMedia.roundBudget()).isEqualTo(Duration.ofMinutes(16));
        assertThat(orphanMedia.roundBudget()).isLessThan(orphanMedia.lockAtMostFor());
    }

    /**
     * 리스 기본값은 스케줄러의 {@code @SchedulerLock} placeholder 와 이 record 두 곳에서 쓰인다.
     * 리터럴을 다시 적으면 <b>기본 배포에서만</b> 예산이 리스보다 길어지는, 테스트로는 안 보이는
     * 상태가 된다. 두 상수를 파싱해 같은 값인지 고정한다.
     */
    @Test
    void leaseDefaultsAreParseableAndSingleSourced() {
        assertThat(Duration.parse(LiveReconcileProperties.EndStaleLive.DEFAULT_LOCK_AT_MOST_FOR))
                .isEqualTo(new LiveReconcileProperties(null, null, null).endStaleLive().lockAtMostFor());
        assertThat(Duration.parse(LiveReconcileProperties.ExpireReady.DEFAULT_LOCK_AT_MOST_FOR))
                .isEqualTo(new LiveReconcileProperties(null, null, null).expireReady().lockAtMostFor());
        assertThat(Duration.parse(LiveReconcileProperties.OrphanMedia.DEFAULT_LOCK_AT_MOST_FOR))
                .isEqualTo(new LiveReconcileProperties(null, null, null).orphanMedia().lockAtMostFor());
    }

    /**
     * 제거된 키를 조용히 무시하면 운영자는 그 값이 먹는다고 믿은 채로 지낸다 — 그 믿음이 깨지는 건
     * 사고 때다. orphan-media 는 이제 개수가 아니라 시간으로 회차를 묶으므로 batch-size 도 폐기됐다.
     */
    @Test
    void removedBatchSizeKeysFailStartup() {
        new ApplicationContextRunner()
                .withUserConfiguration(LiveReconcileConfig.class)
                .withPropertyValues("live.reconcile.batch-size=1")
                .run(context -> assertThat(context).hasFailed());

        new ApplicationContextRunner()
                .withUserConfiguration(LiveReconcileConfig.class)
                .withPropertyValues("live.reconcile.orphan-media.batch-size=10")
                .run(context -> assertThat(context).hasFailed());
    }
}
