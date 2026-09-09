package com.sapari.liveapp.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.beans.factory.ObjectProvider;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 고아 라이브 정리 스케줄링. {@code live.reconcile.enabled=false} 로 세 잡을 한 번에 내릴 수 있다
 * (잡 하나만 끄는 건 각 스케줄러의 {@code @ConditionalOnProperty}).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "live.reconcile", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {

    /** 정리 잡 수. 셋이 서로를 막지 않으려면 스레드도 그만큼 있어야 한다. */
    private static final int POOL_SIZE = 3;

    /**
     * 잡별 기본 cron. <b>단일 출처다</b> — {@code @Scheduled} 와
     * {@link ReconcileLockConfig#lockIntervalsMustFitInCron} 이 같은 값을 봐야 한다. 복제해 두면
     * 스케줄러 쪽만 바꿨을 때 가드가 <b>낡은 기본값을 검증하고 조용히 통과</b>한다.
     *
     * <p>셋을 3분씩 어긋내 둔 것은 세 잡이 동시에 LiveKit 을 때리지 않게 하기 위해서다 — 정렬하지 말 것.
     *
     * <p><b>다만 스태거는 회차가 짧을 때만 유효하다.</b> 회차 예산이 40m/20m/16m 라 적체가 있으면
     * 한 잡의 회차가 다음 잡의 발화를 넘어가고, 풀이 3스레드라 그때는 실제로 겹쳐 돈다(그게 의도다 —
     * 한 잡이 다른 잡을 굶기지 않게 하려고 스레드를 3개 준다). 즉 스태거는 정상 상태의 최적화이지
     * 동시 실행을 막는 장치가 아니다. LiveKit 부하가 문제면 예산이나 주기를 조정할 것.
     */
    public static final String EXPIRE_READY_CRON = "0 0/10 * * * *";
    public static final String END_STALE_LIVE_CRON = "0 3/10 * * * *";
    public static final String ORPHAN_MEDIA_CRON = "0 6/10 * * * *";

    /**
     * 기본 스케줄러는 스레드가 1개라 한 잡이 LiveKit 응답을 기다리는 동안 나머지가 밀린다
     * (호출마다 {@code callTimeout} 15s 가 걸려 있어도, 회차는 후보 수만큼 그게 반복된다).
     * yml 이 아니라 코드로 박는 건 {@code application*.yml} 이 추적되지 않아 환경마다 누락되기 때문이다.
     */
    @Bean
    public TaskScheduler taskScheduler(ObjectProvider<TrackingLockProvider> lockProvider) {
        ThreadPoolTaskScheduler scheduler = new ReconcileTaskScheduler(lockProvider);
        scheduler.setPoolSize(POOL_SIZE);
        scheduler.setThreadNamePrefix("live-reconcile-");
        // 종료 시 진행 중인 회차를 기다린다 — 미디어 정리 도중에 끊기면 고아가 그대로 남는다.
        // 다만 보장은 아니다: expire-ready/end-stale-live 는 후보별 호출을 반복하고 orphan-media 는
        // roundBudget 안에서 방별 리소스 수만큼 호출한다. 각 호출은 최대 callTimeout 15s 라 30초를 쉽게
        // 넘는다. 파드 종료 유예(기본 30s)도 그쯤이다.
        //
        // 정상 종료로 끝난 회차의 락은 ShedLock 이 태스크 finally 에서 스스로 반납한다. 시간이 끝났다고
        // shutdownNow 를 호출해도 OkHttp 동기 execute 는 interrupt 로 취소된다는 보장이 없어 회차는 계속
        // 돌 수 있으므로, 남은 락을 우리가 대신 내리지는 않는다 — 인계는 lock-at-most-for 만료가 담당한다.
        // 실행 중인 회차보다 먼저 락을 내리는 것보다 인계가 늦는 쪽이 파괴 스윕의 상호배제를 보존한다.
        // SIGKILL·OOM·노드 사망에는 훅이 없고, 그때는 짧아진 리스 만료가 유일한 인계 수단이다.
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }

    static final class ReconcileTaskScheduler extends ThreadPoolTaskScheduler {
        private final ObjectProvider<TrackingLockProvider> lockProvider;

        ReconcileTaskScheduler(ObjectProvider<TrackingLockProvider> lockProvider) {
            this.lockProvider = lockProvider;
        }

        @Override
        public void shutdown() {
            try {
                // 빈 생성 순서 때문에 taskScheduler 생성 시점에 아직 provider가 없을 수 있다. 종료 시점에
                // 조회해야 등록이 끝난 뒤의 실제 provider로 새 락 획득을 막는다.
                lockProvider.ifAvailable(TrackingLockProvider::beginShutdown);
            } finally {
                // 종료 중 provider 조회가 실패해도 executor 종료는 반드시 진행한다. 예외는 삼키지 않아
                // 컨테이너가 원인을 기록하게 한다.
                try {
                    super.shutdown();
                } finally {
                    finishShutdown(getScheduledExecutor());
                }
            }
        }

        static void finishShutdown(ScheduledExecutorService executor) {
            if (executor.isTerminated()) {
                return;
            }
            // 새 회차는 beginShutdown 으로 막혔다. 대기 시간을 넘긴 회차에는 중단 신호만 보낸다 —
            // 실행 중인 회차의 락은 뺏지 않고 lock-at-most-for 만료가 인계를 맡는다.
            executor.shutdownNow();
        }
    }
}
