package com.sapari.live.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.sapari.live.application.port.LiveMetrics;
import com.sapari.live.application.port.PromotionTrigger;
import com.sapari.live.application.port.ReconcileAbortReason;
import com.sapari.live.application.port.ReconcileAction;
import com.sapari.live.application.port.ReconcileJob;
import com.sapari.live.domain.model.LiveStatus;

/**
 * {@link LiveMetrics} 의 micrometer 구현.
 *
 * <p><b>전이·승격은 커밋 이후에 센다.</b> 두 사건은 {@code @Transactional} 안에서 보고되는데, 그 뒤
 * 같은 트랜잭션이 롤백되면 일어나지 않은 전이를 센 것이 된다(예: {@code StartLiveService} 는 저장
 * 이후에 상품을 저장하므로 그 단계에서 실패할 수 있다). 여기서 한 번 처리해 두면 서비스마다
 * 커밋 훅을 반복해 붙이지 않아도 되고, 빠뜨릴 자리도 없다. 트랜잭션 밖 호출(정리 잡 통계)은
 * 그대로 즉시 기록한다.
 *
 * <p>회차 통계(round/acted)는 커밋과 무관한 사실이므로 미루지 않는다 — 오히려 미루면 트랜잭션이
 * 없는 잡에서 영영 기록되지 않는다.
 */
@Slf4j
public class MicrometerLiveMetrics implements LiveMetrics {

    /**
     * 중단 사유가 없는 회차의 {@code reason} 값.
     *
     * <p><b>프로메테우스는 같은 이름의 미터가 모두 같은 태그 키 집합을 갖도록 요구한다.</b> 완료/실패에는
     * {@code reason} 이 없고 중단에만 있으면, 먼저 등록된 쪽이 이기고 <b>나중 것은 WARN 한 줄 뒤 조용히
     * 버려진다</b>(예외도 아니고, 그 뒤로는 debug 로 내려가 로그도 안 남는다). 실제로는 완료가 먼저
     * 등록되므로 <b>중단 시계열이 영구 유실된다</b> — 문서가 "가드가 회차를 삼켰다는 유일한 외부 신호"
     * 라고 못박은 바로 그 지표다.
     *
     * <p>{@code SimpleMeterRegistry} 는 이 제약이 없어 단위 테스트로는 드러나지 않는다.
     * {@code PrometheusScrapeTest} 가 실제 스크레이프 출력으로 이 조합을 고정한다.
     */
    private static final String NO_REASON = "none";

    private final MeterRegistry registry;
    private final AtomicInteger liveKitEgressRooms = new AtomicInteger(-1);

    public MicrometerLiveMetrics(MeterRegistry registry) {
        this.registry = registry;
        // -1 로 시작한다 — 0 으로 두면 "정리 잡이 아직 한 번도 안 돌았다" 와 "정말 0 건" 이 같아 보인다.
        Gauge.builder(LiveMeterNames.LIVEKIT_EGRESS_ROOMS, liveKitEgressRooms, AtomicInteger::get)
                .description("정리 잡이 마지막 회차에 관측한, LiveKit 에서 활성 egress 를 가진 방 수 (-1 = 아직 미관측)")
                .register(registry);
    }

    @Override
    public void reconcileRoundCompleted(ReconcileJob job, Duration took) {
        safe(() -> {
            Counter.builder(LiveMeterNames.RECONCILE_ROUND)
                    .tag(LiveMeterNames.TAG_JOB, tag(job))
                    .tag(LiveMeterNames.TAG_OUTCOME, "completed")
                    .tag(LiveMeterNames.TAG_REASON, NO_REASON)
                    .register(registry)
                    .increment();
            Timer.builder(LiveMeterNames.RECONCILE_DURATION)
                    .tag(LiveMeterNames.TAG_JOB, tag(job))
                    .register(registry)
                    .record(took);
        });
    }

    @Override
    public void reconcileRoundAborted(ReconcileJob job, ReconcileAbortReason reason) {
        safe(() -> {
            Counter.builder(LiveMeterNames.RECONCILE_ROUND)
                    .tag(LiveMeterNames.TAG_JOB, tag(job))
                    .tag(LiveMeterNames.TAG_OUTCOME, "aborted")
                    .tag(LiveMeterNames.TAG_REASON, tag(reason))
                    .register(registry)
                    .increment();
        });
    }

    @Override
    public void reconcileRoundFailed(ReconcileJob job) {
        safe(() -> {
            Counter.builder(LiveMeterNames.RECONCILE_ROUND)
                    .tag(LiveMeterNames.TAG_JOB, tag(job))
                    .tag(LiveMeterNames.TAG_OUTCOME, "failed")
                    .tag(LiveMeterNames.TAG_REASON, NO_REASON)
                    .register(registry)
                    .increment();
        });
    }

    @Override
    public void reconcileActed(ReconcileJob job, ReconcileAction action, int count) {
        // 0 도 기록한다 — 시계열이 아예 없는 것과 0 인 것은 대시보드에서 다르게 읽힌다.
        // (없으면 "이 갈래가 사라졌나?" 를 의심하게 되고, 0 이면 "정상적으로 아무 일 없었다" 다.)
        safe(() -> Counter.builder(LiveMeterNames.RECONCILE_ACTED)
                .tag(LiveMeterNames.TAG_JOB, tag(job))
                .tag(LiveMeterNames.TAG_ACTION, tag(action))
                .register(registry)
                .increment(count));
    }

    @Override
    public void roomTransitioned(LiveStatus from, LiveStatus to) {
        afterCommit(() -> safe(() -> Counter.builder(LiveMeterNames.ROOM_TRANSITION)
                .tag(LiveMeterNames.TAG_FROM, statusTag(from))
                .tag(LiveMeterNames.TAG_TO, statusTag(to))
                .register(registry)
                .increment()));
    }

    @Override
    public void rtmpPromoted(PromotionTrigger trigger) {
        afterCommit(() -> safe(() -> Counter.builder(LiveMeterNames.RTMP_PROMOTION)
                .tag(LiveMeterNames.TAG_TRIGGER, tag(trigger))
                .register(registry)
                .increment()));
    }

    @Override
    public void liveKitActiveEgressRooms(int rooms) {
        liveKitEgressRooms.set(rooms);
    }

    /**
     * 계측 실패를 삼킨다 — <b>포트 계약("구현은 절대 예외를 던지지 않는다")을 여기서 지킨다.</b>
     *
     * <p>이게 없으면 두 방향으로 업무가 깨진다. 하나, 커밋 훅에서 던지면 그 예외가 커밋 호출자에게
     * 전파돼 <b>이미 커밋된 Ready→Live 전이 위에 예외가 얹히고</b> 판매자에게는 방송 시작 실패로
     * 보인다. 둘, 정리 잡의 {@code reconcileRoundFailed} 는 예외를 다시 던지기 직전에 호출되므로
     * 여기서 던지면 <b>진짜 실패 원인을 대체</b>해 버린다.
     *
     * <p>조용히 넘기지는 않는다 — 지표가 비는데 원인을 모르는 상태가 되면 안 된다.
     */
    private void safe(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("live 지표 기록 실패 — 업무 처리에는 영향 없음", e);
        }
    }

    /** 트랜잭션 안이면 커밋 이후로 미루고, 밖이면 즉시 실행한다. */
    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private static String tag(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    /**
     * sealed 상태의 태그 값. 레코드 이름을 그대로 쓴다 — 상태가 추가되면 태그도 자동으로 늘어난다.
     * 여기서 {@code switch} 로 나열하면 새 상태를 넣을 때마다 컴파일이 깨지는데, 관측 코드가
     * 도메인 확장을 막는 건 주객전도다.
     */
    private static String statusTag(LiveStatus status) {
        return status == null ? "none" : status.getClass().getSimpleName().toLowerCase(Locale.ROOT);
    }
}
