package com.sapari.liveapp.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

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
import org.springframework.scheduling.TaskScheduler;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import com.sapari.live.port.ReconcileExpiredReadyUseCase;
import com.sapari.live.port.ReconcileOrphanMediaUseCase;
import com.sapari.live.port.ReconcileStaleLiveUseCase;
import com.sapari.liveapp.config.ReconcileLockConfig;
import com.sapari.liveapp.config.SchedulingConfig;

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
        @DisplayName("락 저장소를 live_schema.shedlock 에, DB 시계 기준으로 건다")
        void locksAgainstTheMigratedTableUsingDbTime() {
            JdbcTemplateLockProvider.Configuration configuration =
                    ReconcileLockConfig.lockConfiguration(mock(DataSource.class));

            assertThat(configuration.getTableName())
                    .as("마이그레이션이 만든 테이블과 어긋나면 매 회차 BadSqlGrammarException 이다")
                    .isEqualTo("live_schema.shedlock");
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
         * 스위치 테스트와 같은 이유로 존재한다 — {@code @SchedulerLock} 이 빠져도 컴파일·부팅·실행이
         * 모두 정상이고, 잡은 그냥 <b>락 없이</b> 돈다. 레플리카가 2 이상이면 그게 곧 중복 실행이고
         * 아무 신호도 나지 않는다.
         */
        @Test
        @DisplayName("세 잡 모두 서로 다른 이름의 락을, 잡별 유지 시간으로 건다")
        void everyJobLocksUnderItsOwnName() {
            // 유지 시간까지 여기서 고정하는 이유: 실제 경합·인계 검증인 ReconcileSchedulerLockTest 는
            // @Tag("docker") 로 CI 에서 빠진다. 값이 회귀해도(PT90M → PT5M) CI 는 아무 말이 없다.
            // 잡의 최악 실행 시간 근거는 각 스케줄러 자바독에 있다.
            Map<Class<?>, Map.Entry<String, Duration>> expected = Map.of(
                    ExpireReadyScheduler.class,
                    Map.entry("live-reconcile-expire-ready", Duration.ofMinutes(45)),
                    EndStaleLiveScheduler.class,
                    Map.entry("live-reconcile-end-stale-live", Duration.ofMinutes(90)),
                    OrphanMediaScheduler.class,
                    Map.entry("live-reconcile-orphan-media", Duration.ofMinutes(60)));

            for (Class<?> job : List.of(
                    ExpireReadyScheduler.class, EndStaleLiveScheduler.class, OrphanMediaScheduler.class)) {
                SchedulerLock lock = lockOn(job);
                assertThat(lock).as("@SchedulerLock 이 빠지면 락 없이 돈다: %s", job.getSimpleName()).isNotNull();
                assertThat(lock.name()).isEqualTo(expected.get(job).getKey());
                assertThat(defaultOf(lock.lockAtMostFor())).isEqualTo(expected.get(job).getValue());
                assertThat(defaultOf(lock.lockAtLeastFor())).isEqualTo(Duration.ofMinutes(1));
            }
        }

        private SchedulerLock lockOn(Class<?> job) {
            try {
                return job.getMethod("run").getAnnotation(SchedulerLock.class);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(e);
            }
        }

        /** {@code ${key:PT45M}} 에서 기본값만 꺼낸다 — 운영이 덮어쓰지 않았을 때 실제로 걸리는 값이다. */
        private Duration defaultOf(String placeholder) {
            return Duration.parse(placeholder.substring(placeholder.lastIndexOf(':') + 1, placeholder.length() - 1));
        }
    }
}
