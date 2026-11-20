package com.sapari.chat.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.sapari.chat.application.port.ChatMetrics;
import com.sapari.chat.application.port.RateLimitResult;

import reactor.core.publisher.Flux;

/**
 * Redis 가 죽은 구간의 레이트리밋 — 컨테이너를 띄우지 않는다.
 *
 * <p>장애를 재려는 것이라 진짜 Redis 가 필요 없고, 끊어진 주소로 붙이면 실패하기까지의 시간이 환경에
 * 달려 테스트가 느리고 불안정해진다. 여기서 재는 것은 "연산이 실패했을 때 무엇을 돌려주는가" 와 로컬
 * GCRA 의 규칙이다. 로컬 판정은 시각을 인자로 받으므로 기다리지 않는다.
 */
@DisplayName("RedisRateLimiter — Redis 장애 구간")
class RedisRateLimiterDegradedTest {

    /** 반응형 종결자(.block() / StepVerifier) 상한 — 없으면 회귀가 테스트를 실패시키는 대신 <b>멈추게</b> 한다(근거: modules/chat/AGENTS.md). */
    private static final Duration VERIFY_CEILING = Duration.ofSeconds(10);

    private static final long INTERVAL = RedisRateLimiter.EMISSION_INTERVAL.toNanos();

    private RedisRateLimiter rateLimiter;

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ReactiveStringRedisTemplate templateAnswering(Flux reply) {
        ReactiveStringRedisTemplate template = mock(ReactiveStringRedisTemplate.class);
        given(template.execute(any(RedisScript.class), anyList(), anyList())).willReturn(reply);
        return template;
    }

    @BeforeEach
    void setUpFailingRedis() {
        rateLimiter = new RedisRateLimiter(
                templateAnswering(Flux.error(new RedisConnectionFailureException("프로브 — Redis 가 죽었다"))),
                ChatMetrics.NOOP);
    }

    /** 같은 시각에 버스트를 다 쓴다. */
    private void spendTheBurst(UUID userId, long at) {
        for (int i = 0; i < RedisRateLimiter.BURST; i++) {
            assertThat(rateLimiter.localAcquire(userId, at).allowed()).as("버스트 %d번째", i + 1).isTrue();
        }
    }

    /**
     * ⭐ 답이 없는 Redis 를 기다리지 않는다.
     *
     * <p>장애에는 두 모양이 있고 어댑터는 둘 다 받아야 한다 — 끊긴 Redis(에러)와 <b>답이 느린</b>
     * Redis(pending)다. 후자에 연산자 상한이 없으면 클라이언트 상한까지 매달려
     * {@code onErrorResume} 이 돌지 않고, 전송 경로가 직렬화돼 있어 그 세션의 채팅이 통째로 멈춘다.
     */
    @Test
    @DisplayName("⭐ 답이 없는 Redis 를 기다리지 않는다 — 느린 Redis 가 그 세션의 채팅을 멈추지 않게")
    void a_silent_redis_does_not_block_the_caller() {
        // given: 에러가 아니라 영원히 pending 인 Redis — 파티션·과부하의 모양이다
        RedisRateLimiter silent = new RedisRateLimiter(templateAnswering(Flux.never()), ChatMetrics.NOOP);

        // when & then: block 의 상한은 어댑터 상한보다 넉넉하다 — 어댑터가 안 끊으면 여기서 터진다
        RateLimitResult result = silent.tryAcquire(UUID.randomUUID()).block(Duration.ofSeconds(2));

        assertThat(result).as("상한 안에 판정이 돌아오지 않았다").isNotNull();
        assertThat(result.allowed()).as("강등 판정의 첫 요청은 허용").isTrue();
    }

    @Test
    @DisplayName("⭐ 장애 중에도 상한이 남는다 — 버스트 3 은 허용하되 4번째는 거부한다")
    void keeps_a_cap_while_redis_is_down() {
        // given
        UUID userId = UUID.randomUUID();

        // when
        RateLimitResult[] results = new RateLimitResult[RedisRateLimiter.BURST + 1];
        for (int i = 0; i < results.length; i++) {
            results[i] = rateLimiter.tryAcquire(userId).block(VERIFY_CEILING);
        }

        // then: 전면 통과였다면 회선 속도로 미는 클라 하나가 전부 통과하고, 그 프레임마다 저장이 일어난다
        for (int i = 0; i < RedisRateLimiter.BURST; i++) {
            assertThat(results[i].allowed()).as("장애 중 버스트 %d번째는 허용", i + 1).isTrue();
        }
        assertThat(results[RedisRateLimiter.BURST].allowed())
                .as("버스트를 넘은 요청은 거부 — 장애가 상한을 지우지 않는다").isFalse();
        assertThat(results[RedisRateLimiter.BURST].retryAfterSeconds()).isEqualTo(3);
    }

    @Test
    @DisplayName("⭐ 강등 로그 — 첫 발생은 남고 간격 안의 두 번째는 솎인다")
    void the_degraded_log_is_throttled() {
        // given
        Logger logger = (Logger) LoggerFactory.getLogger(RedisRateLimiter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        // when: 같은 간격(10초) 안에 두 번 실패시킨다
        try {
            rateLimiter.tryAcquire(UUID.randomUUID()).block(VERIFY_CEILING);
            int afterFirst = appender.list.size();
            rateLimiter.tryAcquire(UUID.randomUUID()).block(VERIFY_CEILING);

            // then
            assertThat(afterFirst).as("첫 발생이 남지 않았다").isEqualTo(1);
            assertThat(appender.list).as("간격 안의 두 번째가 솎이지 않았다").hasSize(1);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @DisplayName("⭐ 남은 시간은 올려서 돌려준다 — 내리면 아직 막혔는데 0 이 나가 클라가 곧장 재시도한다")
    void remaining_seconds_are_rounded_up() {
        // given: 버스트를 다 쓰면 다음 허용은 한 간격(3초) 뒤다
        UUID userId = UUID.randomUUID();
        long start = System.nanoTime();
        spendTheBurst(userId, start);

        // when & then: 1.5초 지난 시점 — 남은 1.5초는 2초로 올라간다(내리면 1)
        assertThat(rateLimiter.localAcquire(userId, start + 1_500_000_000L).retryAfterSeconds())
                .as("남은 1.5초").isEqualTo(2);

        // 허용 0.5ms 전 — 올리면 1 이고 내리면 0 이다
        assertThat(rateLimiter.localAcquire(userId, start + INTERVAL - 500_000L).retryAfterSeconds())
                .as("허용 직전").isEqualTo(1);
    }

    @Test
    @DisplayName("장애 중 상한은 userId 단위다 — 한 사람이 막혀도 남이 막히지 않는다")
    void cap_is_per_user() {
        // given
        UUID blocked = UUID.randomUUID();
        long now = System.nanoTime();
        spendTheBurst(blocked, now);

        // when & then
        assertThat(rateLimiter.localAcquire(blocked, now).allowed()).isFalse();
        assertThat(rateLimiter.localAcquire(UUID.randomUUID(), now).allowed()).isTrue();
    }

    @Test
    @DisplayName("⭐ 거부가 TAT 를 밀지 않는다 — 막힌 채로 계속 밀어도 해제가 미뤄지지 않는다")
    void denial_does_not_move_the_tat() {
        // given: 버스트를 다 쓰고 2초 동안 계속 두드린다
        UUID userId = UUID.randomUUID();
        long start = System.nanoTime();
        spendTheBurst(userId, start);
        for (long at = start; at < start + Duration.ofSeconds(2).toNanos(); at += 100_000_000L) {
            assertThat(rateLimiter.localAcquire(userId, at).allowed()).isFalse();
        }

        // when & then: 한 간격이 막 지난 시점
        //
        // 거부가 TAT 를 밀면 마지막 두드림이 해제를 뒤로 옮겨 여기서도 거부다 — 계속 두드리는 클라가
        // 스스로 영구 차단된다. retryAfterSeconds 로는 못 잰다: 초 단위 올림이라 가려진다.
        assertThat(rateLimiter.localAcquire(userId, start + INTERVAL).allowed())
                .as("한 간격이 지났으면 허용 — 두드림이 해제를 밀지 않는다").isTrue();
    }

    @Test
    @DisplayName("⭐ 한 간격마다 한 건이 다시 허용된다 — 평균 3초에 1건으로 수렴한다")
    void one_more_per_interval() {
        // given
        UUID userId = UUID.randomUUID();
        long start = System.nanoTime();
        spendTheBurst(userId, start);

        // when & then
        assertThat(rateLimiter.localAcquire(userId, start + 1).allowed()).isFalse();
        assertThat(rateLimiter.localAcquire(userId, start + INTERVAL).allowed()).as("한 간격 뒤").isTrue();
        assertThat(rateLimiter.localAcquire(userId, start + INTERVAL).allowed()).as("한 간격에 한 건").isFalse();
        assertThat(rateLimiter.localAcquire(userId, start + 2 * INTERVAL).allowed()).as("두 간격 뒤").isTrue();
    }

    /**
     * ⭐ 같은 사용자의 동시 호출이 <b>마지막 자리를 둘 다</b> 가져가지 않는다 — {@code compute} 가
     * 키 단위로 원자적이다.
     *
     * <p>버스트가 있어 "둘 다 통과" 자체는 정상이다. 그래서 라운드마다 <b>자리를 하나만 남긴 상태</b>를
     * 만들고 두 스레드를 들여보낸다. {@code compute} 를 "읽고 나서 쓴다" 로 바꾸면 둘 다 "자리가 있다"
     * 를 보고 통과한다.
     *
     * <p><b>사살이 확률적이고 그 확률이 머신·부하에 달렸다.</b> 라운드마다 배리어로 맞춰도 겹칠 확률은
     * 1보다 작다. 통과 방향은 결정적이므로({@code compute} 는 언제나 0) 이 테스트 자체가 흔들리지는 않는다.
     */
    @Test
    @DisplayName("⭐ 마지막 자리에 동시에 들어와도 둘 다 통과하지 않는다 — 판정이 키 단위로 원자적이다")
    void concurrent_callers_never_both_take_the_last_slot() throws Exception {
        // given
        int rounds = 5_000;
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        int doubleAllowed = 0;

        // when
        try {
            for (int round = 0; round < rounds; round++) {
                UUID userId = UUID.randomUUID();
                long now = System.nanoTime();
                for (int i = 0; i < RedisRateLimiter.BURST - 1; i++) {
                    rateLimiter.localAcquire(userId, now);
                }
                Callable<Boolean> attempt = () -> {
                    barrier.await(30, TimeUnit.SECONDS);
                    return rateLimiter.localAcquire(userId, now).allowed();
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
                .as("마지막 자리를 둘 다 가져갔다 — 버스트가 3 을 넘는다")
                .isZero();
    }

    @Test
    @DisplayName("⭐ 지난 항목은 풀리고 살아 있는 항목은 남는다")
    void expired_entries_are_released_and_live_ones_kept() {
        // given: 쓸기 간격(3초) 안에 두 사람이 보낸다 — old 는 한 건(TAT start+3s),
        // recent 는 두 건(TAT start+1.5s+6s)
        long start = System.nanoTime();
        UUID old = UUID.randomUUID();
        UUID recent = UUID.randomUUID();
        rateLimiter.localAcquire(old, start);
        rateLimiter.localAcquire(recent, start + INTERVAL / 2);
        rateLimiter.localAcquire(recent, start + INTERVAL / 2);
        assertThat(rateLimiter.trackedLocalEntries()).isEqualTo(2);

        // when: 쓸기 간격이 지난 start+4.5s 에 누군가 보낸다 — old 의 TAT 는 지났고 recent 의 TAT 는 아직이다
        rateLimiter.localAcquire(UUID.randomUUID(), start + INTERVAL + INTERVAL / 2);

        // then: old 는 풀리고 recent 와 방금 것이 남는다. 안 쓸면 장애가 길어질수록 항목이 쌓이고,
        // 다 지우면 살아 있는 상한이 풀린다
        assertThat(rateLimiter.trackedLocalEntries()).isEqualTo(2);
        // recent 는 1.5s 에 두 건을 썼고 3초가 지나 한 칸이 찼다 → 지금 남은 자리는 2. 쓸기가 recent 를
        // 지웠다면 처음 상태라 3 이다.
        long now = start + INTERVAL + INTERVAL / 2;
        int allowed = 0;
        for (int i = 0; i < RedisRateLimiter.BURST + 1; i++) {
            if (rateLimiter.localAcquire(recent, now).allowed()) {
                allowed++;
            }
        }
        assertThat(allowed).as("쓸기가 살아 있는 recent 를 지웠다").isEqualTo(2);
    }
}
