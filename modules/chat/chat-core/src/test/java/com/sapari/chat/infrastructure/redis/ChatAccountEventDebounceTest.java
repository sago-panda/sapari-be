package com.sapari.chat.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 계정 이벤트가 <b>창 안에서 한 번만</b> 나가는지 실제 Redis에서 고정한다.
 *
 * <p>필요한 이유는 하나다. 발행은 밴이 새로 걸렸든 이미 있던 것이든 일어나야 하는데(놓친 Pod의 조용한
 * 세션을 회수하는 경로가 그것뿐이다), 그러면 <b>이미 밴된 대상을 반복 강퇴하는 것만으로</b> 호출 횟수가
 * 그대로 함대 전체 스캔 횟수가 된다 — 이벤트 하나가 모든 Pod에서 로컬 세션을 두 번 훑고 이 엔드포인트에는
 * 레이트리밋이 없다.
 *
 * <p>목이 아니라 컨테이너를 쓰는 것은 여기서 깨질 수 있는 것이 <b>Redis의 의미론</b>이기 때문이다.
 * {@code SET NX EX}가 판정과 예약을 한 번에 하지 않으면 동시 강퇴 둘이 함께 통과하고, 그건 목으로는
 * 드러나지 않는다.
 */
@Testcontainers
@DisplayName("계정 이벤트 발행 — 창 안에서는 한 번만 나간다")
class ChatAccountEventDebounceTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7").withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    private UUID userId;

    @BeforeAll
    static void startTemplate() {
        connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getFirstMappedPort());
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void stopTemplate() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void freshUser() {
        userId = UUID.randomUUID();
    }

    /** 실제로 채널에 나간 건수를 센다 — "발행을 시도했나"가 아니라 "나갔나"를 봐야 증폭이 잡힌다. */
    private AtomicInteger countingPublisher(ChatAccountEventRedisPublisher[] out) {
        AtomicInteger sent = new AtomicInteger();
        StringRedisTemplate counting = new StringRedisTemplate(connectionFactory) {
            @Override
            public Long convertAndSend(String channel, Object message) {
                sent.incrementAndGet();
                return super.convertAndSend(channel, message);
            }
        };
        out[0] = new ChatAccountEventRedisPublisher(counting);
        return sent;
    }

    @Test
    @DisplayName("⭐ 같은 사용자를 반복해 알려도 창 안에서는 한 번만 나간다 — 호출 횟수가 곧 함대 스캔 횟수였다")
    void repeatedPublishesCollapseWithinTheWindow() {
        // given
        ChatAccountEventRedisPublisher[] publisher = new ChatAccountEventRedisPublisher[1];
        AtomicInteger sent = countingPublisher(publisher);

        // when: 이미 밴된 대상을 반복 강퇴하는 모양
        for (int i = 0; i < 20; i++) {
            publisher[0].publishBanned(userId);
        }

        // then
        assertThat(sent.get())
                .as("반복 호출이 그대로 발행으로 나간다 — 호출 하나가 전 Pod의 전수 스캔 두 번이다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("다른 사용자는 서로 막지 않는다 — 창은 사용자 단위다")
    void differentUsersAreIndependent() {
        // given
        ChatAccountEventRedisPublisher[] publisher = new ChatAccountEventRedisPublisher[1];
        AtomicInteger sent = countingPublisher(publisher);

        // when
        publisher[0].publishBanned(userId);
        publisher[0].publishBanned(UUID.randomUUID());

        // then
        assertThat(sent.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("⭐ 동시에 몰려도 한 번만 나간다 — 읽고 쓰면 둘이 함께 통과한다")
    void concurrentPublishesStillCollapse() throws Exception {
        // given
        ChatAccountEventRedisPublisher[] publisher = new ChatAccountEventRedisPublisher[1];
        AtomicInteger sent = countingPublisher(publisher);
        int writers = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(writers);

        // when
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < writers; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    publisher[0].publishBanned(userId);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // then: 판정과 예약이 한 실행 안에 있어야 성립한다
        assertThat(sent.get())
                .as("동시 발행이 창을 통과했다 — 판정과 예약이 쪼개져 있다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("창에는 만료가 붙는다 — 없으면 그 사용자는 영영 다시 알려지지 않는다")
    void theWindowExpires() {
        // given
        ChatAccountEventRedisPublisher[] publisher = new ChatAccountEventRedisPublisher[1];
        countingPublisher(publisher);
        publisher[0].publishBanned(userId);

        // then: 회수는 다음 강퇴가 하므로, 만료가 없으면 그 경로가 영영 닫힌다
        Long ttl = redisTemplate.getExpire("chat:account:pub:" + userId, TimeUnit.SECONDS);
        assertThat(ttl).isNotNull().isPositive();
        assertThat(ttl).isLessThanOrEqualTo(Duration.ofSeconds(10).toSeconds());
    }
}
