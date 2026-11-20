package com.sapari.customer.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.lettuce.core.cluster.SlotHash;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.sapari.customer.application.config.SocialSignupAttemptProperties;
import com.sapari.customer.domain.repository.SocialSignupAttemptRepository;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("소셜 회원가입 SID Redis 시도 제어 테스트")
class SocialSignupAttemptRedisRepositoryTest {

    private static final String SIGNUP_SID = "signup-session-id";

    private GenericContainer<?> redis;

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;

    @BeforeAll
    void setUpRedisTemplate() {
        String externalRedisPort = System.getenv("SAPARI_TEST_REDIS_PORT");
        RedisStandaloneConfiguration configuration;
        if (externalRedisPort == null) {
            Assumptions.assumeTrue(
                    DockerClientFactory.instance().isDockerAvailable(),
                    "Docker를 사용할 수 없어 실제 Redis 테스트를 건너뜁니다."
            );
            redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
                    .withExposedPorts(6379);
            redis.start();
            configuration = new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379));
        } else {
            configuration = new RedisStandaloneConfiguration("127.0.0.1", Integer.parseInt(externalRedisPort));
        }
        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    void closeRedisConnection() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
        if (redis != null) {
            redis.stop();
        }
    }

    @BeforeEach
    void flushRedis() {
        try (var connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    @Test
    @DisplayName("같은 SID의 동시 요청은 하나만 획득하고 거절된 요청은 quota를 소모하지 않는다")
    void onlyOneConcurrentRequestAcquiresAndRejectedRequestsDoNotConsumeQuota() throws Exception {
        SocialSignupAttemptRedisRepository repository = repository(5, Duration.ofMinutes(30), Duration.ofMinutes(2));
        int requestCount = 12;
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<SocialSignupAttemptRepository.AcquireResult>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < requestCount; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return repository.tryAcquire(SIGNUP_SID);
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<SocialSignupAttemptRepository.AcquireResult> results = new ArrayList<>();
            for (Future<SocialSignupAttemptRepository.AcquireResult> future : futures) {
                results.add(future.get(5, TimeUnit.SECONDS));
            }

            assertThat(results).filteredOn(result ->
                    result.status() == SocialSignupAttemptRepository.AcquireStatus.ACQUIRED).hasSize(1);
            assertThat(results).filteredOn(result ->
                    result.status() == SocialSignupAttemptRepository.AcquireStatus.ALREADY_PROCESSING)
                    .hasSize(requestCount - 1);
            assertThat(redisTemplate.opsForValue().get(SocialSignupAttemptRedisRepository.attemptKey(SIGNUP_SID)))
                    .isEqualTo("1");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("실제 처리 시도 1회부터 5회까지 허용하고 6번째는 거절한다")
    void allowsFiveSequentialAttemptsAndRejectsSixth() {
        SocialSignupAttemptRedisRepository repository = repository(5, Duration.ofMinutes(30), Duration.ofMinutes(2));

        for (int attempt = 1; attempt <= 5; attempt++) {
            SocialSignupAttemptRepository.AcquireResult result = repository.tryAcquire(SIGNUP_SID);
            assertThat(result.status()).isEqualTo(SocialSignupAttemptRepository.AcquireStatus.ACQUIRED);
            repository.release(SIGNUP_SID, result.leaseToken());
        }

        assertThat(repository.tryAcquire(SIGNUP_SID).status())
                .isEqualTo(SocialSignupAttemptRepository.AcquireStatus.RATE_LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("오래된 소유자는 TTL 뒤 새로 획득한 lock을 삭제할 수 없다")
    void staleOwnerCannotDeleteNewerLock() throws Exception {
        SocialSignupAttemptRedisRepository repository = repository(5, Duration.ofMinutes(30), Duration.ofSeconds(5));
        SocialSignupAttemptRepository.AcquireResult staleOwner = repository.tryAcquire(SIGNUP_SID);
        awaitLockExpiry();
        SocialSignupAttemptRepository.AcquireResult currentOwner = repository.tryAcquire(SIGNUP_SID);

        repository.release(SIGNUP_SID, staleOwner.leaseToken());

        assertThat(repository.tryAcquire(SIGNUP_SID).status())
                .isEqualTo(SocialSignupAttemptRepository.AcquireStatus.ALREADY_PROCESSING);
        repository.release(SIGNUP_SID, currentOwner.leaseToken());
    }

    @Test
    @DisplayName("처리권 해제는 요청한 SID의 lock만 삭제한다")
    void releaseDeletesOnlyTheRequestedSignupSidLock() {
        String otherSignupSid = "other-signup-session-id";
        SocialSignupAttemptRedisRepository repository = repository(5, Duration.ofMinutes(30), Duration.ofMinutes(2));
        SocialSignupAttemptRepository.AcquireResult requestedSidOwner = repository.tryAcquire(SIGNUP_SID);
        SocialSignupAttemptRepository.AcquireResult otherSidOwner = repository.tryAcquire(otherSignupSid);

        repository.release(SIGNUP_SID, requestedSidOwner.leaseToken());

        assertThat(repository.tryAcquire(SIGNUP_SID).status())
                .isEqualTo(SocialSignupAttemptRepository.AcquireStatus.ACQUIRED);
        assertThat(repository.tryAcquire(otherSignupSid).status())
                .isEqualTo(SocialSignupAttemptRepository.AcquireStatus.ALREADY_PROCESSING);
        repository.release(otherSignupSid, otherSidOwner.leaseToken());
    }

    @Test
    @DisplayName("중단된 처리는 lock TTL 뒤 다시 처리권을 획득할 수 있다")
    void abandonedWorkRecoversAfterLockTtl() throws Exception {
        SocialSignupAttemptRedisRepository repository = repository(5, Duration.ofMinutes(30), Duration.ofSeconds(5));
        assertThat(repository.tryAcquire(SIGNUP_SID).status())
                .isEqualTo(SocialSignupAttemptRepository.AcquireStatus.ACQUIRED);

        awaitLockExpiry();

        assertThat(repository.tryAcquire(SIGNUP_SID).status())
                .isEqualTo(SocialSignupAttemptRepository.AcquireStatus.ACQUIRED);
    }

    @Test
    @DisplayName("시도 window TTL은 첫 실제 시도에만 시작하고 후속 시도에서 연장하지 않는다")
    void attemptWindowTtlStartsOnFirstActualAttempt() throws Exception {
        SocialSignupAttemptRedisRepository repository = repository(5, Duration.ofMinutes(1), Duration.ofSeconds(5));
        SocialSignupAttemptRepository.AcquireResult first = repository.tryAcquire(SIGNUP_SID);
        Long firstTtl = redisTemplate.getExpire(
                SocialSignupAttemptRedisRepository.attemptKey(SIGNUP_SID),
                TimeUnit.MILLISECONDS
        );
        repository.release(SIGNUP_SID, first.leaseToken());
        Thread.sleep(300);
        SocialSignupAttemptRepository.AcquireResult second = repository.tryAcquire(SIGNUP_SID);
        Long secondTtl = redisTemplate.getExpire(
                SocialSignupAttemptRedisRepository.attemptKey(SIGNUP_SID),
                TimeUnit.MILLISECONDS
        );
        repository.release(SIGNUP_SID, second.leaseToken());

        assertThat(firstTtl).isPositive();
        assertThat(secondTtl).isLessThan(firstTtl);
    }

    @Test
    @DisplayName("lock과 attempt key는 SID hash tag를 공유한다")
    void keysShareSignupSidHashTag() {
        String lockKey = SocialSignupAttemptRedisRepository.lockKey(SIGNUP_SID);
        String attemptKey = SocialSignupAttemptRedisRepository.attemptKey(SIGNUP_SID);

        assertThat(lockKey).contains("{" + SIGNUP_SID + "}");
        assertThat(attemptKey).contains("{" + SIGNUP_SID + "}");
        assertThat(SlotHash.getSlot(lockKey)).isEqualTo(SlotHash.getSlot(attemptKey));
    }

    private SocialSignupAttemptRedisRepository repository(int maxAttempts, Duration window, Duration lockTtl) {
        return new SocialSignupAttemptRedisRepository(
                redisTemplate,
                new SocialSignupAttemptProperties(maxAttempts, window, lockTtl)
        );
    }

    private void awaitLockExpiry() throws InterruptedException {
        Thread.sleep(5_200);
    }
}
