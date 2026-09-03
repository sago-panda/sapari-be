package com.sapari.live.infrastructure.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import jakarta.annotation.PostConstruct;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.sapari.global.time.TimeProvider;
import com.sapari.live.domain.repository.LiveRoomRepository;

/**
 * DB 기준 "지금 방송 중인 방 수" 게이지.
 *
 * <p>{@code live.livekit.egress.rooms} 와 <b>나란히 놓고 보라고 만든 지표다</b>. 둘이 벌어지는 게
 * live 장애의 공통 증상이다 — DB 가 크면 목록엔 뜨는데 안 열리는 방송(시청자 이탈), LiveKit 이 크면
 * 아무도 안 보는 방송에 계속 비용이 나간다.
 *
 * <p><b>스크레이프당 DB 조회를 {@link #TTL} 로 묶는다.</b> 이 게이지는 전체 지표 중 유일하게 DB 를
 * 건드리는데, {@code /actuator/prometheus} 에는 인증이 없다(스크레이퍼가 로그인할 수단이 없다).
 * 그 주소가 어떤 이유로든 도달 가능해지면 고빈도 호출이 <b>방송 시작과 같은 커넥션 풀</b>을 먹고,
 * 그 경로는 행 잠금을 쥔 채 LiveKit 을 최대 15초 기다리는 가장 무거운 작업이다. 판매자에게는
 * "시작 버튼이 반응하지 않는다" 로 나타난다. 실제 방어는 네트워크지만 그 설정은 아직 없으므로
 * (infra/AGENTS.md) 여기서 한 겹을 더 둔다.
 *
 * <p>TTL 5초는 <b>해상도를 팔지 않는다</b> — 스크레이프 주기가 15초라 매번 새 값을 받는다.
 * (10분 캐시라면 얘기가 다르다: 두 게이지가 벌어지는 순간을 놓친다. 그래서 짧은 TTL 이다.)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActiveRoomGauge {

    /** 스크레이프 주기(보통 15초)보다 짧게 — 그래야 캐시가 그래프의 해상도를 건드리지 않는다. */
    private static final Duration TTL = Duration.ofSeconds(5);

    private final ObjectProvider<MeterRegistry> registryProvider;
    private final LiveRoomRepository liveRoomRepository;
    private final TimeProvider timeProvider;

    /**
     * 값과 관측 시각을 <b>한 쌍으로</b> 갱신한다. 둘을 따로 두면 동시 스크레이프가 끼어들 때 한쪽만
     * 갱신된 상태가 읽혀, 예컨대 실패로 얻은 {@code NaN} 이 남의 타임스탬프를 달고 TTL 만큼 남는다.
     */
    private record Snapshot(double value, Instant at) { }

    private final AtomicReference<Snapshot> cache = new AtomicReference<>();

    /** 갱신 중인 스레드가 있는지. 동시 스크레이프가 같은 만료 시점에 몰려도 조회는 한 번만 나간다. */
    private final AtomicBoolean refreshing = new AtomicBoolean();

    @PostConstruct
    void register() {
        MeterRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            // registry 가 없으면 관측 없이 그대로 돈다. 단위 테스트가 registry 없이 컨텍스트를 띄우기
            // 때문이다. (LiveMetrics.NOOP 과 같은 이유 — 오늘 live-core 를 쓰는 앱은 live-app 하나뿐이라
            // "actuator 없는 다른 앱을 위해" 가 아니다. 확인: grep -rn "live-core" --include=*.gradle)
            return;
        }
        Gauge.builder(LiveMeterNames.ROOM_ACTIVE, this, ActiveRoomGauge::cachedCount)
                .description("DB 기준 LIVE 상태인 방 수 (최대 " + TTL.toSeconds() + "초 캐시)")
                .register(registry);
    }

    /**
     * TTL 안이면 저장된 값을 그대로 준다. 만료됐으면 <b>한 스레드만</b> 조회하고 나머지는 직전 값을 준다.
     *
     * <p>이 한 번만(single-flight)이 핵심이다. 단순 check-then-set 이면 만료되는 순간 동시 스크레이프가
     * 전부 통과해 조회가 동시에 N 번 나가는데, 그 커넥션은 <b>방송 시작이 쓰는 것과 같은 풀</b>이다.
     * 캐시를 넣은 목적이 바로 그 동시 부하를 막는 것이라, 여기가 뚫리면 캐시가 있으나 마나다.
     *
     * <p>잠금은 쓰지 않는다 — 경쟁에서 진 스레드는 <b>기다리지 않고</b> 만료된 직전 값을 돌려준다.
     * 스크레이프 스레드를 붙잡지 않는다는 원래 설계를 지키기 위해서다. 최대 TTL 만큼 낡은 값이 한 번 더
     * 나갈 뿐이고, 다음 스크레이프에는 새 값이 있다.
     */
    private double cachedCount() {
        Instant now = timeProvider.now();
        Snapshot snapshot = cache.get();
        if (snapshot != null && now.isBefore(snapshot.at().plus(TTL))) {
            return snapshot.value();
        }
        if (!refreshing.compareAndSet(false, true)) {
            // 다른 스레드가 갱신 중 — 직전 값으로 답한다. 첫 스크레이프가 겹친 경우에만 값이 없어
            // NaN 이 나가는데, 그건 "아직 관측 못 함"이라 0 을 내보내는 것보다 정직하다.
            return snapshot == null ? Double.NaN : snapshot.value();
        }
        try {
            double value = countOrNaN();
            // 시각은 조회 <b>이후</b>에 다시 읽는다. 조회 전 시각으로 찍으면 조회가 TTL 보다 오래 걸릴 때
            // 저장하자마자 만료된 스냅샷이 되어, 정작 DB 가 느린 상황에서만 캐시가 통째로 무력화된다.
            cache.set(new Snapshot(value, timeProvider.now()));
            return value;
        } finally {
            refreshing.set(false);
        }
    }

    /**
     * 조회 실패를 {@code NaN} 으로 돌린다 — 0 으로 돌리면 "DB 가 안 읽힌다" 가 "방송이 전부 끝났다" 로
     * 보여서, DB 장애가 정상 종료 그래프와 구분되지 않는다. 게이지 supplier 는 스크레이프 스레드에서
     * 도므로 예외를 밖으로 내보내지 않는다.
     *
     * <p><b>실패도 캐시된다.</b> 마지막 성공값을 계속 내보내면 DB 가 죽은 동안에도 화면에는 방송 수가
     * 멀쩡히 떠 있어, 캐시가 장애를 가리게 된다 — 위 NaN 결정을 캐시가 되돌리는 셈이다.
     */
    private double countOrNaN() {
        try {
            return liveRoomRepository.countLiveRooms();
        } catch (RuntimeException e) {
            log.warn("활성 방 수 조회 실패 — 게이지를 NaN 으로 보고", e);
            return Double.NaN;
        }
    }
}
