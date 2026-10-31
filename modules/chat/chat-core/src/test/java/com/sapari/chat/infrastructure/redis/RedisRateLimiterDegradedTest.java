package com.sapari.chat.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;

import com.sapari.chat.application.port.RateLimitResult;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import reactor.core.publisher.Mono;

/**
 * Redis 가 죽은 구간의 레이트리밋 — 컨테이너를 띄우지 않는다.
 *
 * <p>장애를 재려는 것이라 진짜 Redis 가 필요 없고, 끊어진 주소로 붙이면 실패하기까지의 시간이 환경에
 * 달려 테스트가 느리고 불안정해진다. 여기서 재는 것은 "연산이 실패했을 때 무엇을 돌려주는가" 하나다.
 */
@DisplayName("RedisRateLimiter — Redis 장애 구간")
class RedisRateLimiterDegradedTest {


    /** 반응형 종결자(.block() / StepVerifier) 상한 — 없으면 회귀가 테스트를 실패시키는 대신 <b>멈추게</b> 한다(근거: modules/chat/AGENTS.md). */
    private static final Duration VERIFY_CEILING = Duration.ofSeconds(10);
    private RedisRateLimiter rateLimiter;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUpFailingRedis() {
        ReactiveStringRedisTemplate template = mock(ReactiveStringRedisTemplate.class);
        ReactiveValueOperations<String, String> operations = mock(ReactiveValueOperations.class);
        given(template.opsForValue()).willReturn(operations);
        given(operations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .willReturn(Mono.error(new RedisConnectionFailureException("프로브 — Redis 가 죽었다")));
        rateLimiter = new RedisRateLimiter(template);
    }

    /**
     * ⭐ 답이 없는 Redis 를 기다리지 않는다.
     *
     * <p>장애에는 두 모양이 있고 어댑터는 둘 다 받아야 한다 — 끊긴 Redis(에러)와 <b>답이 느린</b>
     * Redis(pending)다. 후자에 연산자 상한이 없으면 클라이언트 상한까지 매달려
     * {@code onErrorResume} 이 돌지 않고, 전송 경로가 직렬화돼 있어 그 세션의 채팅이 통째로 멈춘다.
     */
    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("⭐ 답이 없는 Redis 를 기다리지 않는다 — 느린 Redis 가 그 세션의 채팅을 멈추지 않게")
    void a_silent_redis_does_not_block_the_caller() {
        // given: 에러가 아니라 영원히 pending 인 Redis — 파티션·과부하의 모양이다
        ReactiveStringRedisTemplate template = mock(ReactiveStringRedisTemplate.class);
        ReactiveValueOperations<String, String> operations = mock(ReactiveValueOperations.class);
        given(template.opsForValue()).willReturn(operations);
        given(operations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .willReturn(Mono.never());
        RedisRateLimiter silent = new RedisRateLimiter(template);

        // when & then: block 의 상한은 어댑터 상한보다 넉넉하다 — 어댑터가 안 끊으면 여기서 터진다
        RateLimitResult result = silent.tryAcquire(UUID.randomUUID()).block(Duration.ofSeconds(2));

        assertThat(result).as("상한 안에 판정이 돌아오지 않았다").isNotNull();
        assertThat(result.allowed()).as("강등 판정의 첫 요청은 허용").isTrue();
    }

    /**
     * ⭐ 확정된 거부가 상한 때문에 허용으로 바뀌지 않는다.
     *
     * <p>{@code SET NX} 가 false 를 주면 판정은 끝났다. 그 뒤의 잔여 TTL 조회가 느릴 때 상한이
     * 체인 전체에 걸려 있으면 강등으로 떨어지고, 강등의 첫 판정은 허용이다 — 창 안의 전송이
     * 통과한다(실측: 고치기 전 {@code allowed=true retryAfter=0}).
     */
    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("⭐ 거부가 확정된 뒤 TTL 조회가 느려도 거부로 남는다 — 상한이 판정을 뒤집지 않게")
    void a_confirmed_denial_survives_a_slow_ttl_lookup() {
        // given: SET NX 는 즉시 false(거부 확정), 잔여 TTL 조회만 느리다
        ReactiveStringRedisTemplate template = mock(ReactiveStringRedisTemplate.class);
        ReactiveValueOperations<String, String> operations = mock(ReactiveValueOperations.class);
        given(template.opsForValue()).willReturn(operations);
        given(operations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .willReturn(Mono.just(false));
        given(template.getExpire(anyString()))
                .willReturn(Mono.just(Duration.ofSeconds(2)).delayElement(Duration.ofSeconds(1)));

        // when
        RateLimitResult result = new RedisRateLimiter(template).tryAcquire(UUID.randomUUID())
                .block(Duration.ofSeconds(5));

        // then: 거부로 남고, 모르는 잔여 시간은 창 전체로 답한다
        assertThat(result).isNotNull();
        assertThat(result.allowed()).as("확정된 거부가 허용으로 바뀌었다").isFalse();
        assertThat(result.retryAfterSeconds()).as("모르는 잔여는 창 전체로").isEqualTo(3);
    }

    /**
     * ⭐ 그 신호가 <b>첫 발생은 반드시 남기고 간격 안의 두 번째는 솎는다.</b>
     *
     * <p>카운터만 단언하면 솎기와 "첫 발생은 반드시 남는다"가 아무 테스트도 재지 않는다 — 실측으로
     * {@code shouldLogDeniedTtlFallback()} 을 {@code return false} 로 영구 침묵시켜도 이 클래스가
     * 전부 초록이었다. 흡수를 침묵으로 만들지 않겠다는 것이 그 신호의 목적이므로, 발화 자체를 잰다.
     */
    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("⭐ 거부 뒤 TTL 실패 로그 — 첫 발생은 남고 간격 안의 두 번째는 솎인다")
    void the_denied_ttl_fallback_logs_once_per_interval() {
        // given
        ReactiveStringRedisTemplate template = mock(ReactiveStringRedisTemplate.class);
        ReactiveValueOperations<String, String> operations = mock(ReactiveValueOperations.class);
        given(template.opsForValue()).willReturn(operations);
        given(operations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .willReturn(Mono.just(false));
        given(template.getExpire(anyString()))
                .willReturn(Mono.error(new IllegalStateException("TTL 조회 실패")));
        RedisRateLimiter limiter = new RedisRateLimiter(template);

        Logger logger = (Logger) LoggerFactory.getLogger(RedisRateLimiter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        // when: 같은 간격(10초) 안에 두 번 실패시킨다
        try {
            limiter.tryAcquire(UUID.randomUUID()).block(Duration.ofSeconds(5));
            int afterFirst = appender.list.size();
            limiter.tryAcquire(UUID.randomUUID()).block(Duration.ofSeconds(5));

            // then: 첫 발생은 남고 두 번째는 솎인다. 카운터는 둘 다 올라간다 — 솎기가 지우는 것은
            // 줄 수지 사건 수가 아니다.
            assertThat(afterFirst).as("첫 발생이 남지 않았다 — 흡수가 침묵이 됐다").isEqualTo(1);
            assertThat(appender.list).as("간격 안의 두 번째가 솎이지 않았다").hasSize(1);
            assertThat(limiter.deniedTtlFallbacks()).as("카운터는 둘 다 세야 한다").isEqualTo(2);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    /**
     * ⭐ 거부 뒤 TTL 조회 실패를 흡수하되 <b>침묵하지 않는다.</b>
     *
     * <p>판정을 지키려 흡수하는 것은 맞지만, 그 대가로 거부 경로의 Redis 열화가 카운터도 로그도 없이
     * 사라지면 안 된다 — 상한이 생기기 전에는 같은 실패가 강등 카운터에 잡혔다. 판정은 그대로,
     * 신호만 되돌린 것이 맞는 모양이다.
     */
    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("⭐ 거부 뒤 TTL 조회 실패가 세어진다 — 흡수가 침묵이 되지 않게")
    void a_denied_ttl_fallback_is_counted() {
        // given
        ReactiveStringRedisTemplate template = mock(ReactiveStringRedisTemplate.class);
        ReactiveValueOperations<String, String> operations = mock(ReactiveValueOperations.class);
        given(template.opsForValue()).willReturn(operations);
        given(operations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .willReturn(Mono.just(false));
        given(template.getExpire(anyString()))
                .willReturn(Mono.error(new IllegalStateException("TTL 조회 실패")));
        RedisRateLimiter limiter = new RedisRateLimiter(template);

        // when
        RateLimitResult result = limiter.tryAcquire(UUID.randomUUID()).block(Duration.ofSeconds(5));

        // then: 판정은 거부로 남고, 그 열화가 숫자로 남는다
        assertThat(result).isNotNull();
        assertThat(result.allowed()).isFalse();
        assertThat(limiter.deniedTtlFallbacks())
                .as("거부 뒤 TTL 조회 실패가 세어지지 않았다 — 흡수가 침묵이 됐다").isEqualTo(1);
    }

    @Test
    @DisplayName("⭐ 장애 중에도 상한이 남는다 — 첫 요청은 허용하되 창 안의 두 번째는 거부한다")
    void keeps_a_cap_while_redis_is_down() {
        // given
        UUID userId = UUID.randomUUID();

        // when
        RateLimitResult first = rateLimiter.tryAcquire(userId).block(VERIFY_CEILING);
        RateLimitResult second = rateLimiter.tryAcquire(userId).block(VERIFY_CEILING);

        // then: 전면 통과였다면 회선 속도로 미는 클라 하나가 창 없이 전부 통과하고, 그 프레임마다
        // 저장이 일어난다. 가용성을 지키려 허용하는 것이 "무제한 허용"일 이유는 없다.
        assertThat(first.allowed()).as("장애 중 첫 요청은 허용 — 가용성이 엄격성보다 우선").isTrue();
        assertThat(second.allowed()).as("창 안의 두 번째는 거부 — 장애가 상한을 지우지 않는다").isFalse();
        assertThat(second.retryAfterSeconds()).isEqualTo(3);
    }

    @Test
    @DisplayName("⭐ 남은 시간은 올려서 돌려준다 — 내리면 창이 남았는데 0 이 나가 클라가 곧장 재시도한다")
    void remaining_seconds_are_rounded_up() {
        // given: 창을 열고 합성 시계로 두 지점을 본다. isBetween(1, 3) 으로는 이 계약을 못 잡는다 —
        // 합법 범위 전체를 받아들여 올림을 내림으로 바꿔도 통과한다(실측으로 그 뮤턴트가 생존했다).
        UUID userId = UUID.randomUUID();
        long start = System.nanoTime();
        rateLimiter.localWindow(userId, start);

        // when & then: 1.5초 지난 시점 — 남은 1.5초는 2초로 올라간다(내리면 1)
        assertThat(rateLimiter.localWindow(userId, start + 1_500_000_000L).retryAfterSeconds())
                .as("남은 1.5초").isEqualTo(2);

        // 만료 0.5ms 전 — 올리면 1 이고 내리면 0 이다. 0 이 나가면 클라가 곧장 재시도해
        // 거부 왕복이 한 번 더 늘고, 카운트다운 UI 도 "0초 남음" 으로 멈춘다.
        assertThat(rateLimiter.localWindow(userId, start + 2_999_500_000L).retryAfterSeconds())
                .as("만료 직전").isEqualTo(1);
    }

    @Test
    @DisplayName("장애 중 상한은 userId 단위다 — 한 사람이 막혀도 남이 막히지 않는다")
    void cap_is_per_user() {
        // given
        UUID blocked = UUID.randomUUID();
        rateLimiter.tryAcquire(blocked).block(VERIFY_CEILING);

        // when & then
        assertThat(rateLimiter.tryAcquire(blocked).block(VERIFY_CEILING).allowed()).isFalse();
        assertThat(rateLimiter.tryAcquire(UUID.randomUUID()).block(VERIFY_CEILING).allowed()).isTrue();
    }

    @Test
    @DisplayName("⭐ 거부가 창을 연장하지 않는다 — 막힌 채로 계속 밀어도 해제가 미뤄지지 않는다")
    void denial_does_not_extend_the_window() {
        // given: 창을 열고 그 안에서 2초 동안 계속 두드린다
        UUID userId = UUID.randomUUID();
        long start = System.nanoTime();
        long window = Duration.ofSeconds(3).toNanos();
        rateLimiter.localWindow(userId, start);
        for (long at = start; at < start + Duration.ofSeconds(2).toNanos(); at += 100_000_000L) {
            assertThat(rateLimiter.localWindow(userId, at).allowed()).isFalse();
        }

        // when & then: 처음 열린 창의 끝을 막 지난 시점
        //
        // 거부가 창을 밀면 마지막 두드림(start + 1.9s)이 끝을 start + 4.9s 로 옮겨 여기서도 거부다 —
        // 계속 두드리는 클라가 스스로 영구 차단된다. retryAfterSeconds 로는 이걸 잴 수 없다:
        // 초 단위 올림이라 연장 전후가 똑같이 3 으로 나와 가려진다(실측 — 이 단언을 초로 쓰면 뮤턴트가 생존).
        assertThat(rateLimiter.localWindow(userId, start + window + 1).allowed())
                .as("처음 열린 창이 지났으면 허용 — 두드림이 창을 밀지 않는다").isTrue();
    }

    @Test
    @DisplayName("장애 중에도 창이 지나면 다시 허용된다 — 강등이 영구 차단이 아니다")
    void window_reopens_after_it_passes() {
        // given
        UUID userId = UUID.randomUUID();
        long start = System.nanoTime();
        long afterWindow = start + Duration.ofSeconds(3).toNanos() + 1;

        // when & then
        assertThat(rateLimiter.localWindow(userId, start).allowed()).isTrue();
        assertThat(rateLimiter.localWindow(userId, start + 1).allowed()).isFalse();
        assertThat(rateLimiter.localWindow(userId, afterWindow).allowed())
                .as("창이 지났으면 다시 허용 — 그렇지 않으면 장애 한 번이 영구 차단이 된다").isTrue();
    }

    /**
     * ⭐ 상한을 집행하는 것이 <b>창 자신</b>임을 본다 — 쓸기 틱이 아니다.
     *
     * <p>두 기계장치가 서로를 가려 준다. 쓸기 간격과 창 길이가 둘 다 {@code WINDOW} 라, 항목이
     * 만료됐으면 보통 쓸기도 자격을 얻는다 — 그래서 창 만료 가드를 지워도, 쓸기를 "전부 지움" 으로
     * 바꿔도 스위트가 멀쩡했다(실측으로 두 뮤턴트 모두 생존했다). 운영 결과는 양방향으로 실재한다:
     * 가드가 없으면 차단이 자기 창의 끝이 아니라 쓸기 틱에 풀려 <b>최대 2배 과차단</b>, 쓸기가 전부
     * 지우면 틱마다 모든 창이 사라져 <b>최대 2배 과통과</b>다.
     *
     * <p>분리하는 조건은 "창이 아직 살아 있는데 쓸기는 스로틀에 걸린 시점" 이다. 그 지점에서 통과를
     * 허용할 수 있는 것은 가드뿐이고, 그 지점에 항목이 남아 있어야 하므로 쓸기도 함께 고정된다.
     */
    @Test
    @DisplayName("⭐ 상한을 집행하는 것은 창 자신이다 — 쓸기 틱이 대신 집행하고 있지 않다")
    void the_window_enforces_the_cap_not_the_sweep() {
        // given: t0+0.1s 에 창을 연다 → 그 창은 t0+3.1s 에 끝난다
        UUID lingering = UUID.randomUUID();
        long start = System.nanoTime();
        rateLimiter.localWindow(lingering, start + 100_000_000L);

        // when: t0+3.0s 에 다른 사용자가 들어온다 — 쓸기 간격이 지나 쓸기가 돈다
        rateLimiter.localWindow(UUID.randomUUID(), start + 3_000_000_000L);

        // then: 아직 살아 있는 창은 그 쓸기에서 지워지지 않는다
        assertThat(rateLimiter.trackedLocalWindows())
                .as("쓸기가 만료되지 않은 창까지 지웠다 — 틱마다 모든 상한이 풀린다").isEqualTo(2);

        // 그리고 t0+3.15s — 창은 끝났고(3.1s) 쓸기는 스로틀에 걸려 있다(마지막 쓸기 150ms 전).
        // 즉 여기서 통과시킬 수 있는 것은 창 만료 가드뿐이다. 가드가 없으면 "항목이 있다" 만 보고
        // 거부하므로, 차단이 자기 창의 끝이 아니라 다음 쓸기 틱까지 늘어난다.
        assertThat(rateLimiter.localWindow(lingering, start + 3_150_000_000L).allowed())
                .as("창이 끝났는데 거부했다 — 해제를 쓸기 틱이 결정하고 있다").isTrue();
    }

    /**
     * ⭐ 같은 사용자의 동시 호출에서 둘 다 통과하지 않는다 — {@code compute} 가 키 단위로 원자적이다.
     *
     * <p>{@code compute} 를 "읽고 나서 쓴다" 로 바꾸면 그 사이에 둘 다 "창이 비었다" 를 보고 통과한다.
     * 단일 스레드 테스트로는 그 차이가 안 보여 그 뮤턴트가 생존했다(실측).
     *
     * <p><b>사살이 확률적이고 그 확률이 머신·부하에 달렸다.</b> 라운드마다 두 스레드를 배리어로
     * 맞춰도 겹칠 확률은 1보다 작다. 측정값이 환경마다 갈린다 — 2,000 라운드에서 6회(라운드당
     * ≈0.3%)로 보고된 것이 다른 두 실행에서는 5,000 라운드에 6·4회(≈0.08~0.12%)였다. 뒤쪽이면
     * 5,000 라운드의 사살 확률이 ≈98~99.8% 이고 100% 가 아니다. <b>그래서 이 테스트가 한 번
     * 통과한 것을 "get-then-put 도 안전하다" 로 읽으면 안 된다</b> — 사살 실패와 안전은 구별되지
     * 않는다. 통과 방향은 결정적이므로({@code compute} 는 언제나 0) 이 테스트 자체가 흔들리지는
     * 않는다.
     */
    @Test
    @DisplayName("⭐ 같은 사용자에 동시에 들어와도 둘 다 통과하지 않는다 — 판정이 키 단위로 원자적이다")
    void concurrent_callers_never_both_pass() throws Exception {
        // given
        int rounds = 5_000;
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        int doubleAllowed = 0;

        // when: 라운드마다 새 사용자로 두 스레드를 같은 시각에 들여보낸다. 사용자를 새로 뽑는 이유는
        // 앞 라운드의 창이 남아 뒤 라운드를 전부 거부로 만들면 경합이 재현되지 않기 때문이다.
        try {
            for (int round = 0; round < rounds; round++) {
                UUID userId = UUID.randomUUID();
                long now = System.nanoTime();
                Callable<Boolean> attempt = () -> {
                    barrier.await(30, java.util.concurrent.TimeUnit.SECONDS);
                    return rateLimiter.localWindow(userId, now).allowed();
                };
                Future<Boolean> first = pool.submit(attempt);
                Future<Boolean> second = pool.submit(attempt);
                if (first.get() && second.get()) {
                    doubleAllowed++;
                }
            }
        } finally {
            pool.shutdownNow();
        }

        // then
        assertThat(doubleAllowed)
                .as("같은 사용자의 동시 호출이 둘 다 통과했다 — 창 하나에 두 건이 들어가 상한이 두 배가 된다")
                .isZero();
    }

    @Test
    @DisplayName("⭐ 장애가 끝나면 추적 항목이 풀린다 — 창은 3초면 쓸모없는데 스스로 사라지지 않는다")
    void expired_windows_are_released() {
        // given: 서로 다른 사용자 셋이 장애 구간에 전송했다
        long start = System.nanoTime();
        for (int i = 0; i < 3; i++) {
            rateLimiter.localWindow(UUID.randomUUID(), start);
        }
        assertThat(rateLimiter.trackedLocalWindows()).isEqualTo(3);

        // when: 창이 지난 뒤 누군가 한 번 더 전송한다
        long afterWindow = start + Duration.ofSeconds(3).toNanos() + 1;
        rateLimiter.localWindow(UUID.randomUUID(), afterWindow);

        // then: 지난 셋은 풀리고 방금 것만 남는다. 안 쓸면 장애가 길어질수록 항목이 쌓이고
        // 장애가 끝난 뒤에도 그대로 남아 이 Pod 가 사는 동안 회수되지 않는다.
        assertThat(rateLimiter.trackedLocalWindows()).isEqualTo(1);
    }
}
