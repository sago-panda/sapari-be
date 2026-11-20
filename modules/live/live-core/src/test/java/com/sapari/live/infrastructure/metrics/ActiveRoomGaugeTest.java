package com.sapari.live.infrastructure.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.sapari.global.time.TimeProvider;
import com.sapari.live.domain.repository.LiveRoomRepository;

/**
 * 게이지의 두 가지 비자명한 선택을 고정한다.
 *
 * <p>하나는 <b>조회 실패를 0 이 아니라 NaN 으로 돌린다</b>는 것. 0 으로 돌리면 "DB 가 안 읽힌다" 가
 * "방송이 전부 끝났다" 로 보여서, 장애 그래프와 정상 종료 그래프가 구분되지 않는다.
 *
 * <p>다른 하나는 <b>registry 가 없으면 조용히 지나간다</b>는 것. 단위 테스트가 registry 없이 컨텍스트를
 * 띄우기 때문이다. (오늘 live-core 를 쓰는 앱은 live-app 하나뿐이라 "actuator 없는 다른 앱을 위해" 가
 * 아니다 — 확인: grep -rn "live-core" --include=*.gradle)
 */
class ActiveRoomGaugeTest {

    private static final Instant T0 = Instant.parse("2026-09-03T00:00:00Z");

    private final LiveRoomRepository repository = mock(LiveRoomRepository.class);
    private final TimeProvider timeProvider = mock(TimeProvider.class);

    /**
     * 등록한 게이지 객체를 <b>테스트가 끝날 때까지 강하게 붙잡는다</b>.
     *
     * <p>micrometer 의 {@code Gauge.builder(name, obj, fn)} 는 {@code obj} 를 <b>약한 참조</b>로 들고
     * 있다. 지역 변수로만 두면 GC 가 수거하는 순간 게이지가 함수를 부르지 않고 조용히 {@code NaN} 을
     * 돌려준다 — 예외도, 로그도 없다. 로컬에서는 GC 가 안 돌아 통과하고 CI 에서만 깨진다(실제로 그랬다).
     */
    private ActiveRoomGauge registered;

    @Test
    @DisplayName("registry 가 있으면 게이지를 등록하고 DB 값을 그대로 노출한다")
    void withRegistry_registersGauge() {
        MeterRegistry registry = new SimpleMeterRegistry();
        given(timeProvider.now()).willReturn(T0);
        given(repository.countLiveRooms()).willReturn(7L);

        gauge(registry).register();

        assertThat(registry.get("live.room.active").gauge().value()).isEqualTo(7.0);
    }

    @Test
    @DisplayName("조회가 실패하면 NaN 이다 — 0 이면 DB 장애가 '방송이 다 끝났다' 로 읽힌다")
    void queryFailure_reportsNaN() {
        MeterRegistry registry = new SimpleMeterRegistry();
        given(timeProvider.now()).willReturn(T0);
        given(repository.countLiveRooms()).willThrow(new IllegalStateException("DB 장애"));

        gauge(registry).register();

        assertThat(registry.get("live.room.active").gauge().value()).isNaN();
    }

    @Test
    @DisplayName("registry 가 없으면 아무 일도 하지 않는다 — 관측 설정 없이도 컨텍스트가 떠야 한다")
    void withoutRegistry_isNoOp() {
        gauge(null).register();
        // 예외 없이 끝나는 것 자체가 검증 대상이다. DB 도 건드리지 않는다.
        org.mockito.BDDMockito.then(repository).shouldHaveNoInteractions();
    }


    @Test
    @DisplayName("TTL 안의 반복 스크레이프는 DB 를 다시 치지 않는다 — 인증 없는 주소라 고빈도 호출이 방송 시작과 커넥션을 다툰다")
    void withinTtl_doesNotHitDbAgain() {
        MeterRegistry registry = new SimpleMeterRegistry();
        given(timeProvider.now()).willReturn(T0, T0.plusSeconds(1), T0.plusSeconds(4));
        given(repository.countLiveRooms()).willReturn(7L);
        gauge(registry).register();

        double first = registry.get("live.room.active").gauge().value();
        double second = registry.get("live.room.active").gauge().value();
        double third = registry.get("live.room.active").gauge().value();

        assertThat(first).isEqualTo(7.0);
        assertThat(second).isEqualTo(7.0);
        assertThat(third).isEqualTo(7.0);
        org.mockito.BDDMockito.then(repository).should(org.mockito.Mockito.times(1)).countLiveRooms();
    }

    @Test
    @DisplayName("TTL 이 지나면 다시 조회한다 — 캐시가 그래프 해상도를 먹지 않는다")
    void afterTtl_queriesAgain() {
        MeterRegistry registry = new SimpleMeterRegistry();
        // 갱신 1회당 now() 를 두 번 읽는다 — 만료 판정용(진입 시)과 스냅샷 기록용(조회 후).
        // 기록용을 조회 후에 읽는 것은 느린 조회가 스냅샷을 태어나자마자 만료시키지 않게 하기 위함이다.
        given(timeProvider.now()).willReturn(T0, T0, T0.plusSeconds(6), T0.plusSeconds(6));
        given(repository.countLiveRooms()).willReturn(7L, 9L);
        gauge(registry).register();

        assertThat(registry.get("live.room.active").gauge().value()).isEqualTo(7.0);
        assertThat(registry.get("live.room.active").gauge().value()).isEqualTo(9.0);
    }

    @Test
    @DisplayName("실패도 캐시한다 — 마지막 성공값을 계속 내보내면 캐시가 DB 장애를 가린다")
    void failure_isCachedToo() {
        MeterRegistry registry = new SimpleMeterRegistry();
        given(timeProvider.now()).willReturn(T0, T0.plusSeconds(1));
        given(repository.countLiveRooms()).willThrow(new IllegalStateException("DB 장애"));
        gauge(registry).register();

        assertThat(registry.get("live.room.active").gauge().value()).isNaN();
        assertThat(registry.get("live.room.active").gauge().value()).isNaN();
        org.mockito.BDDMockito.then(repository).should(org.mockito.Mockito.times(1)).countLiveRooms();
    }


    @Test
    @DisplayName("만료 시점에 동시 스크레이프가 몰려도 DB 조회는 한 번뿐 — 캐시를 넣은 목적이 그 동시 부하다")
    void concurrentScrapesAtExpiry_queryOnce() throws Exception {
        MeterRegistry registry = new SimpleMeterRegistry();
        given(timeProvider.now()).willReturn(T0);
        given(repository.countLiveRooms()).willAnswer(invocation -> {
            Thread.sleep(60);   // 조회가 도는 동안 다른 스레드가 만료 창에 들어오게 한다
            return 7L;
        });
        gauge(registry).register();

        int threads = 8;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<Double>> results = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return registry.get("live.room.active").gauge().value();
            }));
        }
        start.countDown();
        for (java.util.concurrent.Future<Double> f : results) {
            f.get();
        }
        pool.shutdown();

        org.mockito.BDDMockito.then(repository).should(org.mockito.Mockito.times(1)).countLiveRooms();
    }

    private ActiveRoomGauge gauge(MeterRegistry registry) {
        registered = new ActiveRoomGauge(providerOf(registry), repository, timeProvider);
        return registered;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MeterRegistry> providerOf(MeterRegistry registry) {
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        given(provider.getIfAvailable()).willReturn(registry);
        return provider;
    }
}
