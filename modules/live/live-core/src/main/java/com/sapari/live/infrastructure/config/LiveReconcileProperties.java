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
        @Valid ExpireReady expireReady
) {
    public LiveReconcileProperties {
        if (orphanMedia == null) {
            orphanMedia = new OrphanMedia(null, null);
        }
        if (endStaleLive == null) {
            endStaleLive = new EndStaleLive(null, null, null);
        }
        if(expireReady == null){
            expireReady = new ExpireReady(null, null, null);
        }
    }

    /**
     * @param threshold Live 로 전이한 지 이만큼 지난 방만 후보로 본다. 실제 종료 판정은 활성 egress 유무다.
     * @param batchSize 한 회차에 처리할 최대 방 수. 후보당 왕복 수는 방의 화질 수에 비례해 <b>설정으로
     *                  늘어나므로</b>, 회차 길이를 이 값만으로 묶을 수는 없다 — 시간 쪽은
     *                  {@code lock-at-most-for} 에서 파생한 회차 예산이 맡는다.
     * @param lockAtMostFor ShedLock 리스. <b>{@code @SchedulerLock} 과 같은 프로퍼티 키를 읽는다</b> —
     *                      회차 예산을 여기서 파생시키므로 둘이 갈리면 예산이 리스보다 길어져
     *                      가두려던 것을 못 가둔다.
     */
    public record EndStaleLive(
            Duration threshold,
            Integer batchSize,
            Duration lockAtMostFor
    ) {
        private static final Duration DEFAULT_THRESHOLD = Duration.ofMinutes(60);
        private static final int DEFAULT_BATCH_SIZE = 10;
        /**
         * 리스 기본값. <b>단일 출처다</b> — {@code EndStaleLiveScheduler} 의 {@code @SchedulerLock}
         * placeholder 가 이 상수를 이어 붙여 쓴다. 어노테이션 인자라 컴파일 상수여야 해서 문자열이다.
         *
         * <p>복제하면 안 되는 이유가 이 잡에만 하나 더 있다: 회차 예산을 이 값에서 파생시키므로,
         * 스케줄러 쪽만 낮추면 예산이 리스보다 길어져 <b>가두려던 것을 못 가둔다</b>. 같은 키를 읽으니
         * 설정된 값은 갈릴 수 없고, 갈릴 수 있는 건 기본값뿐이라 그 하나를 여기로 모은다.
         */
        public static final String DEFAULT_LOCK_AT_MOST_FOR = "PT25M";

        /**
         * 회차가 후보 루프에 쓸 수 있는 시간. 리스에서 여유를 뺀 값이다.
         *
         * <p>여유가 필요한 이유는 마감을 후보 <b>사이</b>에서만 보기 때문이다 — 예산을 다 쓴 순간
         * 마지막 후보의 정리가 아직 돌고 있을 수 있다. 1/5 는 화질 3종 기준 한 후보의 관측치
         * (8왕복 × 15s = 2분)에 여유를 둔 기본값일 뿐, 방의 리소스 수에는 코드상 절대 상한이 없어
         * 리스 안 완료를 보장하지는 않는다. 그 경우에도 다음 후보 진입은 막지만, 진행 중인 한 방은
         * 리스 만료 뒤에도 계속될 수 있다.
         */
        public Duration roundBudget() {
            return lockAtMostFor.multipliedBy(4).dividedBy(5);
        }

        public EndStaleLive {
            if (threshold == null) {
                threshold = DEFAULT_THRESHOLD;
            }
            if (threshold.isZero() || threshold.isNegative()) {
                throw new IllegalArgumentException(
                        "live.reconcile.end-stale-live.threshold 는 양수여야 합니다: " + threshold);
            }
            if (batchSize == null) {
                batchSize = DEFAULT_BATCH_SIZE;
            }
            if (batchSize <= 0) {
                throw new IllegalArgumentException(
                        "live.reconcile.end-stale-live.batch-size 는 양수여야 합니다: " + batchSize);
            }
            if (lockAtMostFor == null) {
                lockAtMostFor = Duration.parse(DEFAULT_LOCK_AT_MOST_FOR);
            }
            if (lockAtMostFor.isZero() || lockAtMostFor.isNegative()) {
                throw new IllegalArgumentException(
                        "live.reconcile.end-stale-live.lock-at-most-for 는 양수여야 합니다: " + lockAtMostFor);
            }
        }
    }

    /**
     * @param grace 이만큼 지난 리소스만 회수한다. 방금 생성돼 아직 DB 에 반영되지 않은 정상 리소스를
     *              지우지 않기 위한 유예이므로 0 이 될 수 없다.
     * @param lockAtMostFor ShedLock 리스. 회차 예산을 여기서 파생시킨다.
     */
    public record OrphanMedia(
            Duration grace,
            Duration lockAtMostFor
    ) {
        private static final Duration DEFAULT_GRACE = Duration.ofMinutes(15);
        /** 리스 기본값. 단일 출처 — {@link EndStaleLive#DEFAULT_LOCK_AT_MOST_FOR} 와 같은 이유. */
        public static final String DEFAULT_LOCK_AT_MOST_FOR = "PT20M";

        /** 회차 예산. {@link EndStaleLive#roundBudget()} 과 같은 계산·같은 이유다. */
        public Duration roundBudget() {
            return lockAtMostFor.multipliedBy(4).dividedBy(5);
        }

        public OrphanMedia {
            if (grace == null) {
                grace = DEFAULT_GRACE;
            }
            if (grace.isZero() || grace.isNegative()) {
                throw new IllegalArgumentException("live.reconcile.orphan-media.grace 는 양수여야 합니다: " + grace);
            }
            if (lockAtMostFor == null) {
                lockAtMostFor = Duration.parse(DEFAULT_LOCK_AT_MOST_FOR);
            }
            if (lockAtMostFor.isZero() || lockAtMostFor.isNegative()) {
                throw new IllegalArgumentException(
                        "live.reconcile.orphan-media.lock-at-most-for 는 양수여야 합니다: " + lockAtMostFor);
            }
        }
    }

    /**
     * @param threshold 이만큼 Ready 에 머문 방은 만료시킨다. EndStaleLive 와 달리 이 시간이 곧 판정이다
     * @param batchSize 후보마다 LiveKit 을 왕복하므로 따로 둔다. 왕복은 후보당 1회가 아니다 —
     *                  조회 1회에 더해 처리까지 따라온다:
     *                  만료는 {@code stopHlsEgress}(목록 + 화질 3건 중단) + {@code deleteIngress}(목록 + 삭제)
     *                  + {@code closeRoom} 으로 <b>최대 8회</b>, 승격은 {@code startHlsEgress} 3회다.
     *                  그 정리가 {@code afterCommit} 이라 같은 스케줄러 스레드에서 동기로 돈다.
     *                  <p>다만 {@code callTimeout} 15s 는 <b>타임아웃이지 지연이 아니다</b> — 정상 지연에서는
     *                  20건이 수십 초로 끝난다. 최악은 LiveKit 이 모든 호출에서 멎어야 나오는 값이고,
     *                  그 상황이면 batch-size 를 뭘로 잡든 이미 고장이다. 20 은 한 회차의 처리량을
     *                  정하는 값이지 최악을 주기 안에 넣는 값이 아니다 — 그건 {@link #roundBudget()} 이 한다.
     */
    public record ExpireReady(
        Duration threshold,
        Integer batchSize,
        Duration lockAtMostFor
    ){
        private static final Duration DEFAULT_THRESHOLD = Duration.ofMinutes(60);
        private static final int DEFAULT_BATCH_SIZE = 20;
        /** 리스 기본값. 단일 출처 — {@link EndStaleLive#DEFAULT_LOCK_AT_MOST_FOR} 와 같은 이유. */
        public static final String DEFAULT_LOCK_AT_MOST_FOR = "PT50M";

        /**
         * 회차 예산. {@link EndStaleLive#roundBudget()} 과 같은 계산·같은 이유다 — 이 잡도
         * {@code PostCommitMediaCleanup} 을 공유하므로 후보당 왕복 수가 방의 화질 수에 비례해
         * 고정이 아니다. "후보당 9왕복" 은 그 전제가 유지될 때만 맞는 추정이라 리스 계산의 근거로는
         * 쓰지 않는다.
         */
        public Duration roundBudget() {
            return lockAtMostFor.multipliedBy(4).dividedBy(5);
        }

        public ExpireReady {
            if(threshold == null){
                threshold = DEFAULT_THRESHOLD;
            }
            if(threshold.isZero() || threshold.isNegative()){
                throw new IllegalArgumentException("live.reconcile.expire-ready.threshold 는 양수여야 합니다: " + threshold);
            }
            if (batchSize == null) {
                batchSize = DEFAULT_BATCH_SIZE;
            }
            if (batchSize <= 0) {
                throw new IllegalArgumentException(
                        "live.reconcile.expire-ready.batch-size 는 양수여야 합니다: " + batchSize);
            }
            if (lockAtMostFor == null) {
                lockAtMostFor = Duration.parse(DEFAULT_LOCK_AT_MOST_FOR);
            }
            if (lockAtMostFor.isZero() || lockAtMostFor.isNegative()) {
                throw new IllegalArgumentException(
                        "live.reconcile.expire-ready.lock-at-most-for 는 양수여야 합니다: " + lockAtMostFor);
            }
        }
    }
}
