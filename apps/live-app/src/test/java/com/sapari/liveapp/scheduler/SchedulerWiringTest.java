package com.sapari.liveapp.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.TaskScheduler;

import com.sapari.live.port.ReconcileExpiredReadyUseCase;
import com.sapari.live.port.ReconcileOrphanMediaUseCase;
import com.sapari.live.port.ReconcileStaleLiveUseCase;
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
}
