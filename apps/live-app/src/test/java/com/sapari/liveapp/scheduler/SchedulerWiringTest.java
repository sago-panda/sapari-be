package com.sapari.liveapp.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import com.sapari.live.port.ReconcileExpiredReadyUseCase;
import com.sapari.live.application.port.LiveMetrics;
import com.sapari.live.port.ReconcileOrphanMediaUseCase;
import com.sapari.live.port.ReconcileStaleLiveUseCase;
import com.sapari.liveapp.config.ReconcileLockConfig;
import com.sapari.liveapp.config.SchedulingConfig;
import com.sapari.liveapp.config.TrackingLockProvider;
import com.sapari.liveapp.config.ShedLockTableGuard;

/**
 * 스케줄러 배선 — 잡을 끄는 스위치가 실제로 붙어 있는지 확인한다.
 *
 * <p>{@code @ConditionalOnProperty} 는 접두사를 틀려도 컴파일·부팅·실행이 모두 정상이고,
 * {@code matchIfMissing = true} 때문에 "못 찾은 키"가 "켜짐"으로 읽힌다. 즉 스위치가 빠져도
 * 아무 신호가 없다 — 그 회귀를 잡는 게 이 테스트의 존재 이유다.
 *
 * <p>잡의 판정 로직은 live-core 서비스 테스트가 담당한다. 여기서는 컨텍스트 배선만 본다.
 */
class SchedulerWiringTest {

    @Test
    @DisplayName("정상 종료가 시작되면 새 회차의 락 획득을 막는다")
    void shutdownStopsNewLockAcquisition() {
        TrackingLockProvider lockProvider = mock(TrackingLockProvider.class);
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(TrackingLockProvider.class, () -> lockProvider)
                .withUserConfiguration(SchedulingConfig.class);

        runner.run(context -> {
            context.close();
            verify(lockProvider).beginShutdown();
        });
    }

    private ApplicationContextRunner runnerFor(Class<?> scheduler) {
        return new ApplicationContextRunner()
                .withBean(ReconcileExpiredReadyUseCase.class, () -> mock(ReconcileExpiredReadyUseCase.class))
                .withBean(ReconcileStaleLiveUseCase.class, () -> mock(ReconcileStaleLiveUseCase.class))
                .withBean(ReconcileOrphanMediaUseCase.class, () -> mock(ReconcileOrphanMediaUseCase.class))
                .withUserConfiguration(scheduler);
    }

    @Nested
    @DisplayName("Ready 고착 만료")
    class ExpireReady {

        @Test
        @DisplayName("설정이 없으면 등록된다 — application*.yml 이 추적되지 않아 미설정이 기본 상태다")
        void registeredByDefault() {
            runnerFor(ExpireReadyScheduler.class)
                    .run(context -> assertThat(context).hasSingleBean(ExpireReadyScheduler.class));
        }

        @Test
        @DisplayName("enabled=false 면 등록되지 않는다")
        void disabledByProperty() {
            runnerFor(ExpireReadyScheduler.class)
                    .withPropertyValues("live.reconcile.expire-ready.enabled=false")
                    .run(context -> assertThat(context).doesNotHaveBean(ExpireReadyScheduler.class));
        }

        @Test
        @DisplayName("다른 잡을 꺼도 영향받지 않는다")
        void unaffectedByOtherJobsSwitch() {
            runnerFor(ExpireReadyScheduler.class)
                    .withPropertyValues("live.reconcile.end-stale-live.enabled=false")
                    .run(context -> assertThat(context).hasSingleBean(ExpireReadyScheduler.class));
        }
    }

    @Nested
    @DisplayName("방치된 Live 방 종료")
    class EndStaleLive {

        @Test
        @DisplayName("설정이 없으면 등록된다")
        void registeredByDefault() {
            runnerFor(EndStaleLiveScheduler.class)
                    .run(context -> assertThat(context).hasSingleBean(EndStaleLiveScheduler.class));
        }

        @Test
        @DisplayName("enabled=false 면 등록되지 않는다 — 살아 있는 방송을 끊을 수 있어 이 잡만 즉시 내릴 수 있어야 한다")
        void disabledByProperty() {
            runnerFor(EndStaleLiveScheduler.class)
                    .withPropertyValues("live.reconcile.end-stale-live.enabled=false")
                    .run(context -> assertThat(context).doesNotHaveBean(EndStaleLiveScheduler.class));
        }

        @Test
        @DisplayName("다른 잡을 꺼도 영향받지 않는다")
        void unaffectedByOtherJobsSwitch() {
            runnerFor(EndStaleLiveScheduler.class)
                    .withPropertyValues("live.reconcile.expire-ready.enabled=false")
                    .run(context -> assertThat(context).hasSingleBean(EndStaleLiveScheduler.class));
        }
    }

    @Nested
    @DisplayName("고아 미디어 정리")
    class OrphanMedia {

        @Test
        @DisplayName("설정이 없으면 등록된다")
        void registeredByDefault() {
            runnerFor(OrphanMediaScheduler.class)
                    .run(context -> assertThat(context).hasSingleBean(OrphanMediaScheduler.class));
        }

        @Test
        @DisplayName("enabled=false 면 등록되지 않는다")
        void disabledByProperty() {
            runnerFor(OrphanMediaScheduler.class)
                    .withPropertyValues("live.reconcile.orphan-media.enabled=false")
                    .run(context -> assertThat(context).doesNotHaveBean(OrphanMediaScheduler.class));
        }
    }

    @Nested
    @DisplayName("스케줄링 전체 설정")
    class Scheduling {

        private final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                .withUserConfiguration(SchedulingConfig.class);

        @Test
        @DisplayName("전용 TaskScheduler 를 등록한다 — 기본 스레드 1개면 세 잡이 서로를 막는다")
        void registersDedicatedScheduler() {
            runner.run(context -> {
                assertThat(context).hasSingleBean(TaskScheduler.class);
                assertThat(context.getBean(TaskScheduler.class)).isSameAs(context.getBean("taskScheduler"));
            });
        }

        @Test
        @DisplayName("live.reconcile.enabled=false 면 스케줄링 자체가 뜨지 않는다 (마스터 스위치)")
        void masterSwitch() {
            runner.withPropertyValues("live.reconcile.enabled=false")
                    .run(context -> assertThat(context).doesNotHaveBean(SchedulingConfig.class));
        }

        @Test
        @DisplayName("마스터 스위치를 끄면 잡 빈도 등록되지 않는다 — @EnableScheduling 이 다른 데서 켜져도 돌지 않게")
        void masterSwitchAlsoRemovesJobs() {
            new ApplicationContextRunner()
                    .withBean(ReconcileExpiredReadyUseCase.class, () -> mock(ReconcileExpiredReadyUseCase.class))
                    .withBean(ReconcileStaleLiveUseCase.class, () -> mock(ReconcileStaleLiveUseCase.class))
                    .withBean(ReconcileOrphanMediaUseCase.class, () -> mock(ReconcileOrphanMediaUseCase.class))
                    .withUserConfiguration(ExpireReadyScheduler.class, EndStaleLiveScheduler.class,
                            OrphanMediaScheduler.class)
                    .withPropertyValues("live.reconcile.enabled=false")
                    .run(context -> {
                        assertThat(context).doesNotHaveBean(ExpireReadyScheduler.class);
                        assertThat(context).doesNotHaveBean(EndStaleLiveScheduler.class);
                        assertThat(context).doesNotHaveBean(OrphanMediaScheduler.class);
                    });
        }
    }

    @Nested
    @DisplayName("실패 격리")
    class FailureIsolation {

        @Test
        @DisplayName("유스케이스가 던져도 스케줄러 밖으로 전파되지 않는다 — 다음 회차가 재시도한다")
        void exceptionDoesNotEscape() {
            ReconcileStaleLiveUseCase useCase = mock(ReconcileStaleLiveUseCase.class);
            willThrow(new IllegalStateException("LiveKit 장애")).given(useCase).reconcile();

            assertThatCode(() -> new EndStaleLiveScheduler(useCase).run()).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("분산 락")
    class DistributedLock {

        private final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withBean(LiveMetrics.class, () -> LiveMetrics.NOOP)
                .withUserConfiguration(ReconcileLockConfig.class);

        @Test
        @DisplayName("설정이 없으면 락 프로바이더가 등록된다 — 레플리카를 늘리는 쪽이 기본이어야 한다")
        void registeredByDefault() {
            runner.run(context -> assertThat(context).hasSingleBean(LockProvider.class));
        }

        /**
         * 값까지 보는 이유: 실제 동작 검증인 {@code ReconcileSchedulerLockTest} 는 {@code @Tag("docker")}
         * 로 CI 에서 빠진다. 테이블명 오타는 <b>매 회차 예외</b>로, {@code usingDbTime()} 누락은
         * <b>인스턴스 시계 오차만큼의 상호배제 구멍</b>으로 이어지는데 둘 다 컴파일은 통과한다.
         */
        @Test
        @DisplayName("lock-at-least-for 가 cron 주기의 절반을 넘으면 부팅을 거부한다")
        void refusesLockAtLeastForLongerThanHalfTheCronPeriod() {
            // 주기 직전까지 허용하면 다음 tick 이 락을 잡을 창이 몇 분으로 줄어, tick 이 한 번만
            // 밀려도 회차가 조용히 사라진다. 기본 cron 3종은 모두 10분 주기다.
            runner.withPropertyValues("live.reconcile.lock-at-least-for=PT6M")
                    .run(context -> assertThat(context).hasFailed());

            runner.withPropertyValues("live.reconcile.lock-at-least-for=PT5M")
                    .run(context -> assertThat(context).hasNotFailed());
        }

        /**
         * {@code 0/N} 은 시 경계에서 간격이 줄어든다 — 첫 간격만 재면 이 설정이 통과하고
         * 매시 한 회차만 조용히 사라진다. 가드가 막으려던 바로 그 상태라 여기서 고정한다.
         */
        @Test
        @DisplayName("주기는 첫 간격이 아니라 최단 간격으로 잰다 — 0/7 은 시 경계에서 4분이다")
        void periodIsTheShortestGapNotTheFirstOne() {
            runner.withPropertyValues(
                            "live.reconcile.end-stale-live.cron=0 0/7 * * * *",  // 첫 간격 7분, 최단 4분
                            "live.reconcile.lock-at-least-for=PT3M")             // 7분 기준이면 통과했을 값
                    .run(context -> assertThat(context).hasFailed());

            runner.withPropertyValues(
                            "live.reconcile.end-stale-live.cron=0 0/7 * * * *",
                            "live.reconcile.lock-at-least-for=PT2M")
                    .run(context -> assertThat(context).hasNotFailed());
        }

        /**
         * 잡별 리스 키를 세 경로가 <b>같은 값</b>으로 읽는지 고정한다: {@code @SchedulerLock} 의
         * placeholder, 이 가드의 {@code environment.getProperty}, 그리고 {@code @ConfigurationProperties}
         * 바인딩. 회차 예산을 리스에서 파생시키므로 셋이 갈리면 예산이 실제 리스보다 길어지는데,
         * 그때 아무 신호도 없다(락이 만료된 채 파괴 스윕이 겹쳐 돈다).
         *
         * <p>지금은 Spring Boot 가 환경에 {@code ConfigurationPropertySources} 를 붙여 두어
         * placeholder 도 relaxed 로 풀리므로 케밥/카멜 어느 쪽으로 적어도 셋이 일치한다. 그 성질에
         * 기대고 있으니 여기서 못박는다 — camelCase 로만 적은 5분 리스가 <b>가드에 걸려야</b> 한다.
         */
        @Test
        @DisplayName("리스 키는 케밥/카멜 어느 쪽으로 적어도 가드·@SchedulerLock·바인딩이 같은 값을 본다")
        void leaseKeyResolvesIdenticallyOnEveryPath() {
            runner.withPropertyValues("live.reconcile.orphanMedia.lockAtMostFor=PT5M")
                    .run(context -> assertThat(context)
                            .as("가드가 relaxed 키를 못 보면 조용히 통과한다 — 그게 회귀다")
                            .hasFailed());
        }

        /**
         * 리스가 주기보다 짧으면 다음 tick 이 늘 만료된 락을 본다 — 락을 건 이유가 사라지는데
         * 로그도 지표도 없다. 하한을 상수로 두지 않고 주기에서 끌어오는 게 요점이다.
         */
        @Test
        @DisplayName("잡별 lock-at-most-for 가 그 잡의 cron 주기보다 짧으면 부팅을 거부한다")
        void refusesLeaseShorterThanItsCronPeriod() {
            runner.withPropertyValues("live.reconcile.orphan-media.lock-at-most-for=PT5M")
                    .run(context -> assertThat(context).hasFailed());

            runner.withPropertyValues("live.reconcile.orphan-media.lock-at-most-for=PT10M")
                    .run(context -> assertThat(context).hasNotFailed());
        }

        @Test
        @DisplayName("꺼 둔 잡의 cron 은 검사하지 않는다 — 없는 빈 때문에 부팅이 막히면 안 된다")
        void ignoresCronOfDisabledJobs() {
            runner.withPropertyValues(
                            "live.reconcile.end-stale-live.enabled=false",
                            "live.reconcile.end-stale-live.cron=0 * * * * *",  // 1분 주기지만 잡이 없다
                            "live.reconcile.lock-at-least-for=PT5M")
                    .run(context -> assertThat(context).hasNotFailed());
        }

        @Test
        @DisplayName("락 저장소를 live_schema.shedlock 에, DB 시계 기준으로 건다")
        void locksAgainstTheMigratedTableUsingDbTime() {
            JdbcTemplateLockProvider.Configuration configuration =
                    ReconcileLockConfig.lockConfiguration(mock(DataSource.class));

            assertThat(configuration.getTableName())
                    .as("마이그레이션이 만든 테이블과 어긋나면 매 회차 BadSqlGrammarException 이다")
                    .isEqualTo(ReconcileLockConfig.LOCK_TABLE);
            assertThat(configuration.getUseDbTime())
                    .as("DB 시계로 판정해야 인스턴스 시계 오차가 상호배제를 깨지 못한다")
                    .isTrue();
        }

        @Test
        @DisplayName("마스터 스위치를 끄면 락 설정도 함께 내려간다 — 잡이 없으면 락도 필요 없다")
        void masterSwitchAlsoRemovesLock() {
            runner.withPropertyValues("live.reconcile.enabled=false")
                    .run(context -> assertThat(context).doesNotHaveBean(ReconcileLockConfig.class));
        }

        /**
         * 가드가 스위치를 안 보면 <b>정리 잡을 끈 환경까지 부팅을 막는다</b> — 락이 필요 없는데
         * 마이그레이션을 요구하는 꼴이다. 잡·락 설정과 같은 스위치를 쓰는지 여기서 고정한다.
         */
        @Test
        @DisplayName("마스터 스위치를 끄면 부팅 가드도 내려간다 — 잡이 없으면 락 테이블도 필요 없다")
        void masterSwitchAlsoRemovesBootGuard() {
            ApplicationContextRunner guardRunner = new ApplicationContextRunner()
                    .withBean(JdbcTemplate.class, () -> {
                        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
                        given(jdbcTemplate.update(anyString(), any(Object[].class))).willReturn(1);
                        return jdbcTemplate;
                    })
                    .withUserConfiguration(ShedLockTableGuard.class);

            guardRunner.run(context -> assertThat(context).hasSingleBean(ShedLockTableGuard.class));
            guardRunner.withPropertyValues("live.reconcile.enabled=false")
                    .run(context -> assertThat(context).doesNotHaveBean(ShedLockTableGuard.class));
        }

        /**
         * 스위치 테스트와 같은 이유로 존재한다 — {@code @SchedulerLock} 이 빠져도 컴파일·부팅·실행이
         * 모두 정상이고, 잡은 그냥 <b>락 없이</b> 돈다. 레플리카가 2 이상이면 그게 곧 중복 실행이고
         * 아무 신호도 나지 않는다.
         */
        @Test
        @DisplayName("세 잡 모두 서로 다른 이름의 락을, 잡별 유지 시간으로 건다")
        void everyJobLocksUnderItsOwnName() {
            // 유지 시간까지 여기서 고정하는 이유: 실제 경합·인계 검증인 ReconcileSchedulerLockTest 는
            // @Tag("docker") 로 CI 에서 빠진다. 값이 조용히 줄어도 CI 는 아무 말이 없는데, 리스는
            // 인계 지연이자 <회차 예산의 원천>이라(예산 = 리스 × 4/5) 줄면 처리량까지 함께 준다.
            Map<Class<?>, Map.Entry<String, Duration>> expected = Map.of(
                    ExpireReadyScheduler.class,
                    Map.entry("live-reconcile-expire-ready", Duration.ofMinutes(50)),
                    EndStaleLiveScheduler.class,
                    Map.entry("live-reconcile-end-stale-live", Duration.ofMinutes(25)),
                    OrphanMediaScheduler.class,
                    Map.entry("live-reconcile-orphan-media", Duration.ofMinutes(20)));

            for (Class<?> job : List.of(
                    ExpireReadyScheduler.class, EndStaleLiveScheduler.class, OrphanMediaScheduler.class)) {
                SchedulerLock lock = lockOn(job);
                assertThat(lock).as("@SchedulerLock 이 빠지면 락 없이 돈다: %s", job.getSimpleName()).isNotNull();
                assertThat(lock.name()).isEqualTo(expected.get(job).getKey());
                assertThat(defaultOf(lock.lockAtMostFor())).isEqualTo(expected.get(job).getValue());
                assertThat(defaultOf(lock.lockAtLeastFor())).isEqualTo(Duration.ofMinutes(1));
            }
            assertThat(defaultOf(lockOn(ExpireReadyScheduler.class).lockAtMostFor()))
                    .isGreaterThan(Duration.ofMinutes(45));
            assertThat(defaultOf(lockOn(EndStaleLiveScheduler.class).lockAtMostFor()))
                    .isGreaterThan(Duration.ofMinutes(20).plusSeconds(15));
            assertThat(defaultOf(lockOn(OrphanMediaScheduler.class).lockAtMostFor()))
                    .isGreaterThan(Duration.ofMinutes(15).plusSeconds(45));
        }

        private SchedulerLock lockOn(Class<?> job) {
            try {
                return job.getMethod("run").getAnnotation(SchedulerLock.class);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(e);
            }
        }

        /** {@code ${key:PT50M}} 에서 기본값만 꺼낸다 — 운영이 덮어쓰지 않았을 때 실제로 걸리는 값이다. */
        private Duration defaultOf(String placeholder) {
            return Duration.parse(placeholder.substring(placeholder.lastIndexOf(':') + 1, placeholder.length() - 1));
        }
    }
}
