package com.sapari.productapp.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.sapari.product.domain.exception.BulkUploadRateLimitedException;
import com.sapari.product.domain.exception.BulkUploadUnavailableException;
import com.sapari.product.domain.exception.ProductErrorCode;

import io.lettuce.core.cluster.SlotHash;

/**
 * 판매자별 대량 등록 한도를 실제 Redis(Lua 스크립트·TTL)로 검증한다. 창 만료는 실시간으로 기다리지 않고 키 TTL을 직접 조작한다.
 */
@Testcontainers
@DisplayName("BulkUploadRateLimiter (Redis 통합)")
class BulkUploadRateLimiterTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.4").withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static BulkUploadRateLimiter limiter;

    private UUID sellerId;

    /**
     * 컨테이너 Redis에 붙는 템플릿과 판정기를 만든다.
     */
    @BeforeAll
    static void startTemplate() {
        connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getFirstMappedPort());
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        limiter = new BulkUploadRateLimiter(redisTemplate);
    }

    /**
     * 연결을 닫는다.
     */
    @AfterAll
    static void stopTemplate() {
        connectionFactory.destroy();
    }

    /**
     * 테스트마다 새 판매자를 써서 키가 섞이지 않게 한다.
     */
    @BeforeEach
    void newSeller() {
        sellerId = UUID.randomUUID();
    }

    /**
     * 한 건을 끝까지 처리한 것처럼 잡았다가 바로 내려놓는다.
     */
    private void completeOneUpload() {
        limiter.release(sellerId, limiter.acquire(sellerId));
    }

    @Nested
    @DisplayName("동시 처리 1건")
    class Concurrency {

        @Test
        @DisplayName("첫 요청은 통과하고, 처리 중 표시에 토큰을 TTL(5분 이내)과 함께 남기며 건수 1을 창 TTL(10분 이내)로 센다")
        void firstAcquireSetsLockAndCount() {
            String token = limiter.acquire(sellerId);

            assertThat(token).isNotBlank();
            assertThat(redisTemplate.opsForValue().get(BulkUploadRateLimiter.lockKey(sellerId))).isEqualTo(token);
            assertThat(redisTemplate.getExpire(BulkUploadRateLimiter.lockKey(sellerId), TimeUnit.MILLISECONDS))
                    .isPositive()
                    .isLessThanOrEqualTo(BulkUploadRateLimiter.LOCK_TTL.toMillis());
            assertThat(redisTemplate.opsForValue().get(BulkUploadRateLimiter.countKey(sellerId))).isEqualTo("1");
            assertThat(redisTemplate.getExpire(BulkUploadRateLimiter.countKey(sellerId), TimeUnit.MILLISECONDS))
                    .isPositive()
                    .isLessThanOrEqualTo(BulkUploadRateLimiter.WINDOW.toMillis());
        }

        @Test
        @DisplayName("처리 중이면 429(Retry-After 10초)로 거절하고, 거절된 요청은 건수를 쓰지 않는다")
        void rejectsWhileBusyWithoutCounting() {
            String token = limiter.acquire(sellerId);

            assertThatThrownBy(() -> limiter.acquire(sellerId))
                    .isInstanceOfSatisfying(BulkUploadRateLimitedException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(ProductErrorCode.BULK_UPLOAD_RATE_LIMITED);
                        assertThat(e.getRetryAfterSeconds())
                                .isEqualTo(BulkUploadRateLimiter.CONCURRENT_RETRY_AFTER.toSeconds());
                    });
            assertThat(redisTemplate.opsForValue().get(BulkUploadRateLimiter.countKey(sellerId))).isEqualTo("1");
            // 거절이 남의 처리 중 표시를 바꾸지 않는다
            assertThat(redisTemplate.opsForValue().get(BulkUploadRateLimiter.lockKey(sellerId))).isEqualTo(token);
        }

        @Test
        @DisplayName("내려놓으면 다음 업로드가 통과한다(새 토큰)")
        void releaseAllowsNextUpload() {
            String first = limiter.acquire(sellerId);
            limiter.release(sellerId, first);

            String second = limiter.acquire(sellerId);

            assertThat(second).isNotEqualTo(first);
            assertThat(redisTemplate.opsForValue().get(BulkUploadRateLimiter.countKey(sellerId))).isEqualTo("2");
        }

        @Test
        @DisplayName("다른 토큰으로는 처리 중 표시를 지우지 않는다 — TTL이 지나 다음 요청이 잡은 표시를 앞 요청이 지우지 않게")
        void releaseWithForeignTokenKeepsLock() {
            String current = limiter.acquire(sellerId);

            limiter.release(sellerId, UUID.randomUUID().toString());

            assertThat(redisTemplate.opsForValue().get(BulkUploadRateLimiter.lockKey(sellerId))).isEqualTo(current);
            assertThatThrownBy(() -> limiter.acquire(sellerId)).isInstanceOf(BulkUploadRateLimitedException.class);
        }

        @Test
        @DisplayName("처리 중 표시가 만료되면(해제 실패·Pod 종료) 다음 업로드가 통과한다")
        void expiredLockAllowsNextUpload() {
            limiter.acquire(sellerId);
            redisTemplate.delete(BulkUploadRateLimiter.lockKey(sellerId)); // TTL 만료와 같은 상태

            assertThatCode(() -> limiter.acquire(sellerId)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("다른 판매자의 처리 중 표시·건수와는 서로 독립이다")
        void sellersAreIndependent() {
            UUID other = UUID.randomUUID();
            limiter.acquire(sellerId);

            assertThatCode(() -> limiter.acquire(other)).doesNotThrowAnyException();
            assertThat(redisTemplate.opsForValue().get(BulkUploadRateLimiter.countKey(other))).isEqualTo("1");
        }

        @Test
        @DisplayName("같은 판매자가 동시에 8건을 보내면 정확히 1건만 통과하고, 건수도 1만 쓴다 (원자적 판정)")
        void simultaneousRequestsAdmitExactlyOne() throws Exception {
            int threads = 8;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            try {
                for (int i = 0; i < threads; i++) {
                    Callable<Boolean> attempt = () -> {
                        start.await();
                        try {
                            limiter.acquire(sellerId);
                            return true;
                        } catch (BulkUploadRateLimitedException e) {
                            return false;
                        }
                    };
                    results.add(pool.submit(attempt));
                }
                start.countDown();

                int admitted = 0;
                for (Future<Boolean> result : results) {
                    if (result.get(10, TimeUnit.SECONDS)) {
                        admitted++;
                    }
                }
                assertThat(admitted).isEqualTo(1);
                assertThat(redisTemplate.opsForValue().get(BulkUploadRateLimiter.countKey(sellerId))).isEqualTo("1");
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Nested
    @DisplayName("10분 10건")
    class Window {

        @Test
        @DisplayName("10건째까지 통과하고 11건째는 429로 거절한다 — 처리 중 표시는 잡지 않는다 (경계)")
        void eleventhUploadIsRejected() {
            for (int i = 0; i < BulkUploadRateLimiter.MAX_UPLOADS_PER_WINDOW; i++) {
                completeOneUpload();
            }

            assertThatThrownBy(() -> limiter.acquire(sellerId))
                    .isInstanceOfSatisfying(BulkUploadRateLimitedException.class, e ->
                            assertThat(e.getRetryAfterSeconds())
                                    .isPositive()
                                    .isLessThanOrEqualTo(BulkUploadRateLimiter.WINDOW.toSeconds()));
            assertThat(redisTemplate.hasKey(BulkUploadRateLimiter.lockKey(sellerId))).isFalse();
        }

        @Test
        @DisplayName("Retry-After는 창의 남은 시간을 초 단위로 올림한다 (1,500ms → 2초)")
        void retryAfterRoundsRemainingWindowUp() {
            for (int i = 0; i < BulkUploadRateLimiter.MAX_UPLOADS_PER_WINDOW; i++) {
                completeOneUpload();
            }
            redisTemplate.expire(BulkUploadRateLimiter.countKey(sellerId), Duration.ofMillis(1_500));

            assertThatThrownBy(() -> limiter.acquire(sellerId))
                    .isInstanceOfSatisfying(BulkUploadRateLimitedException.class, e ->
                            assertThat(e.getRetryAfterSeconds()).isEqualTo(2L));
        }

        @Test
        @DisplayName("한도 초과로 거절된 요청은 창 만료 시각을 늘리지 않는다")
        void rejectionDoesNotExtendWindow() {
            for (int i = 0; i < BulkUploadRateLimiter.MAX_UPLOADS_PER_WINDOW; i++) {
                completeOneUpload();
            }
            redisTemplate.expire(BulkUploadRateLimiter.countKey(sellerId), Duration.ofSeconds(30));

            for (int i = 0; i < 3; i++) {
                assertThatThrownBy(() -> limiter.acquire(sellerId)).isInstanceOf(BulkUploadRateLimitedException.class);
            }

            assertThat(redisTemplate.getExpire(BulkUploadRateLimiter.countKey(sellerId), TimeUnit.SECONDS))
                    .isLessThanOrEqualTo(30L);
        }

        @Test
        @DisplayName("창이 만료되면 건수가 1부터 다시 시작한다")
        void expiredWindowStartsOver() {
            for (int i = 0; i < BulkUploadRateLimiter.MAX_UPLOADS_PER_WINDOW; i++) {
                completeOneUpload();
            }
            redisTemplate.delete(BulkUploadRateLimiter.countKey(sellerId)); // TTL 만료와 같은 상태

            assertThatCode(() -> limiter.acquire(sellerId)).doesNotThrowAnyException();
            assertThat(redisTemplate.opsForValue().get(BulkUploadRateLimiter.countKey(sellerId))).isEqualTo("1");
        }
    }

    @Nested
    @DisplayName("키와 장애")
    class KeysAndFailure {

        @Test
        @DisplayName("처리 중 표시와 건수 키는 같은 Cluster 슬롯에 놓인다 (Lua 다중 키 조건)")
        void keysShareClusterSlot() {
            assertThat(SlotHash.getSlot(BulkUploadRateLimiter.lockKey(sellerId)))
                    .isEqualTo(SlotHash.getSlot(BulkUploadRateLimiter.countKey(sellerId)));
        }

        @Test
        @DisplayName("Redis에 닿지 못하면 판정은 503 예외로 거절하고(fail-closed), 해제는 예외 없이 넘어간다")
        void unreachableRedisFailsClosedOnAcquireOnly() {
            // 열려 있지 않은 포트 — 연결 거부가 바로 난다
            LettuceConnectionFactory deadFactory = new LettuceConnectionFactory("localhost", 1);
            deadFactory.afterPropertiesSet();
            try {
                StringRedisTemplate deadTemplate = new StringRedisTemplate(deadFactory);
                deadTemplate.afterPropertiesSet();
                BulkUploadRateLimiter deadLimiter = new BulkUploadRateLimiter(deadTemplate);

                assertThatThrownBy(() -> deadLimiter.acquire(sellerId))
                        .isInstanceOfSatisfying(BulkUploadUnavailableException.class, e -> {
                            assertThat(e.getErrorCode()).isEqualTo(ProductErrorCode.BULK_UPLOAD_UNAVAILABLE);
                            assertThat(e.getCause()).isNotNull();
                        });
                assertThatCode(() -> deadLimiter.release(sellerId, "token")).doesNotThrowAnyException();
            } finally {
                deadFactory.destroy();
            }
        }

        @Test
        @DisplayName("스크립트는 실행됐는데 응답만 타임아웃 나면, 503을 던지기 전에 같은 토큰으로 처리 중 표시를 지워 재시도가 429에 묶이지 않는다")
        void replyLostAfterScriptRanReleasesLockBeforeFailing() {
            StringRedisTemplate lossyTemplate = spy(redisTemplate);
            // 첫 호출(판정 스크립트)만: 실제로 실행한 뒤 응답 유실을 흉내 낸다. 이후 호출(해제)은 그대로 실행한다.
            doAnswer(invocation -> {
                invocation.callRealMethod();
                throw new QueryTimeoutException("reply lost");
            }).doCallRealMethod()
                    .when(lossyTemplate)
                    .execute(any(RedisScript.class), anyList(), any(Object[].class));
            BulkUploadRateLimiter lossyLimiter = new BulkUploadRateLimiter(lossyTemplate);

            assertThatThrownBy(() -> lossyLimiter.acquire(sellerId))
                    .isInstanceOfSatisfying(BulkUploadUnavailableException.class,
                            e -> assertThat(e.getCause()).isInstanceOf(QueryTimeoutException.class));

            // 스크립트는 실행됐다(건수 1) — 그러나 처리 중 표시는 남지 않는다
            assertThat(redisTemplate.opsForValue().get(BulkUploadRateLimiter.countKey(sellerId))).isEqualTo("1");
            assertThat(redisTemplate.hasKey(BulkUploadRateLimiter.lockKey(sellerId))).isFalse();
            // 바로 다시 시도하면 통과한다(유실된 1건은 건수에 남는다)
            assertThatCode(() -> limiter.acquire(sellerId)).doesNotThrowAnyException();
            assertThat(redisTemplate.opsForValue().get(BulkUploadRateLimiter.countKey(sellerId))).isEqualTo("2");
        }
    }
}
