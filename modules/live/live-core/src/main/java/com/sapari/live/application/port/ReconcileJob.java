package com.sapari.live.application.port;

/**
 * 정리 잡 3종의 식별자. 지표 태그로 쓴다.
 *
 * <p>문자열이 아니라 enum 인 이유: 태그 오타는 예외 없이 <b>새 시계열</b>을 만들고, 그 시계열은 어떤
 * 대시보드에도 잡히지 않는다. 잘못된 값이 조용히 통계에서 사라지는 실패라 컴파일러가 막게 한다.
 */
public enum ReconcileJob {
    EXPIRE_READY(Locks.EXPIRE_READY),
    END_STALE_LIVE(Locks.END_STALE_LIVE),
    ORPHAN_MEDIA(Locks.ORPHAN_MEDIA);

    /**
     * ShedLock 락 이름. <b>단일 출처다</b> — {@code @SchedulerLock(name = ...)} 은 어노테이션 인자라
     * 컴파일 상수를 요구해서 {@link ReconcileJob#lockName()} 을 못 쓴다. 그래서 스케줄러가 여기를
     * 참조하고, enum 도 여기서 값을 받는다(별도 클래스인 건 enum 상수가 자기 클래스의 static 필드를
     * 앞서 참조할 수 없다는 자바 제약 때문이다).
     *
     * <p>복제하면 안 되는 이유: {@code TrackingLockProvider} 가 <b>락 이름 문자열로</b> 잡을 역매핑해
     * 지표 태그를 붙인다. 스케줄러 쪽 리터럴만 바뀌면 역매핑이 실패해 {@code job == null} 이 되고,
     * 그 잡의 {@code live.reconcile.lock} 시계열이 <b>조용히 사라진다</b> — 락 경합과 죽은 스케줄러를
     * 구분하려고 만든 지표라, 없어지는 게 정확히 이 지표를 만든 이유를 무효화한다.
     */
    public static final class Locks {
        public static final String EXPIRE_READY = "live-reconcile-expire-ready";
        public static final String END_STALE_LIVE = "live-reconcile-end-stale-live";
        public static final String ORPHAN_MEDIA = "live-reconcile-orphan-media";

        private Locks() {
        }
    }

    private final String lockName;

    ReconcileJob(String lockName) {
        this.lockName = lockName;
    }

    public String lockName() {
        return lockName;
    }
}
