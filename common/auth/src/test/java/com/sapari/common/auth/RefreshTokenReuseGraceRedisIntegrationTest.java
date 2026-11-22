package com.sapari.common.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.sapari.common.securityjwt.jwt.JwtProperties;
import com.sapari.common.securityjwt.jwt.JwtTestKeys;
import com.sapari.common.securityjwt.jwt.JwtTokenLifecycle;
import com.sapari.common.securityjwt.jwt.JwtTokenLifecycleException;
import com.sapari.common.securityjwt.jwt.JwtTokenPrincipal;
import com.sapari.common.securityjwt.jwt.JwtTokenProvider;
import com.sapari.global.time.TimeProvider;

/**
 * 실제 Redis 로 RTR 재사용 유예를 검증한다 — 회전 스크립트(Lua)와 세션 폐기가 함께 맞물리는지가 대상이다.
 * Docker 가 없으면 건너뛰고, SAPARI_TEST_REDIS_PORT 가 있으면 그 Redis 를 쓴다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Refresh Token 재사용 유예 Redis 통합 테스트")
class RefreshTokenReuseGraceRedisIntegrationTest {

    private static final Duration GRACE = Duration.ofSeconds(5);
    // SPR-215 이전 회전 스크립트 그대로 — 섞여 도는 Pod·롤백 호환성 확인용.
    private static final DefaultRedisScript<Long> PREVIOUS_VERSION_ROTATE_SCRIPT = new DefaultRedisScript<>(
            """
            local current = redis.call('GET', KEYS[1])
            if current == false then
                return 0
            end
            if current ~= ARGV[1] then
                return 0
            end
            redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
            return 1
            """,
            Long.class
    );

    private GenericContainer<?> redis;
    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;

    private MutableClock clock;
    private JwtTokenLifecycle lifecycle;
    private SessionRevocationRedisRepository sessionRevocationRepository;
    private JwtTokenPrincipal principal;

    @BeforeAll
    void setUpRedis() {
        String externalRedisPort = System.getenv("SAPARI_TEST_REDIS_PORT");
        RedisStandaloneConfiguration configuration;
        if (externalRedisPort == null) {
            Assumptions.assumeTrue(
                    DockerClientFactory.instance().isDockerAvailable(),
                    "Docker를 사용할 수 없어 실제 Redis 테스트를 건너뜁니다."
            );
            redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);
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
    void tearDownRedis() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
        if (redis != null) {
            redis.stop();
        }
    }

    @BeforeEach
    void setUp() {
        try (var connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
        clock = new MutableClock(Instant.parse("2026-11-21T00:00:00Z"));
        TimeProvider timeProvider = new TimeProvider(clock);
        JwtProperties jwtProperties = JwtTestKeys.properties("sapari-test", 1800L, 1_209_600L);
        sessionRevocationRepository = new SessionRevocationRedisRepository(redisTemplate, jwtProperties, timeProvider);
        lifecycle = new JwtTokenLifecycle(
                new JwtTokenProvider(jwtProperties, timeProvider),
                new RefreshTokenRedisRepository(redisTemplate, timeProvider, GRACE),
                sessionRevocationRepository,
                new AccessTokenBlacklistRedisRepository(redisTemplate),
                timeProvider
        );
        principal = new JwtTokenPrincipal(UUID.randomUUID(), "USER", "nickname", "user@example.com");
    }

    @Test
    @DisplayName("회전 직후 유예 안에 직전 refresh 가 다시 오면 세션을 지키고 access 만 새로 준다")
    void reuseWithinGraceKeepsSession() {
        String original = lifecycle.issueTokenPair(principal).refreshToken();
        JwtTokenLifecycle.RotatedRefreshToken first = reissue(original);

        clock.advance(GRACE.minusSeconds(1));
        JwtTokenLifecycle.RotatedRefreshToken second = reissue(original);

        assertThat(first.refreshToken()).isNotNull();
        assertThat(second.accessToken()).isNotBlank();
        assertThat(second.refreshToken()).isNull();
        UUID sessionId = lifecycle.requireRefreshToken(original).sessionId();
        assertThat(sessionRevocationRepository.isRevoked(sessionId)).isFalse();
        // 먼저 회전한 쪽의 새 refresh 는 계속 쓸 수 있다.
        assertThat(reissue(first.refreshToken()).refreshToken()).isNotNull();
    }

    @Test
    @DisplayName("유예가 지난 뒤 직전 refresh 가 오면 기존처럼 재사용으로 보고 세션을 폐기한다")
    void reuseAfterGraceRevokesSession() {
        String original = lifecycle.issueTokenPair(principal).refreshToken();
        JwtTokenLifecycle.RotatedRefreshToken first = reissue(original);

        clock.advance(GRACE);

        assertThatThrownBy(() -> reissue(original)).isInstanceOf(JwtTokenLifecycleException.class);
        UUID sessionId = lifecycle.requireRefreshToken(original).sessionId();
        assertThat(sessionRevocationRepository.isRevoked(sessionId)).isTrue();
        assertThatThrownBy(() -> reissue(first.refreshToken())).isInstanceOf(JwtTokenLifecycleException.class);
    }

    @Test
    @DisplayName("같은 refresh 로 동시에 재발급 2건이 오면 하나는 회전, 하나는 유예로 둘 다 성공하고 세션은 산다")
    void concurrentReissueBothSucceed() throws Exception {
        String original = lifecycle.issueTokenPair(principal).refreshToken();

        List<JwtTokenLifecycle.RotatedRefreshToken> results = runConcurrently(2, () -> reissue(original));

        assertThat(results).extracting(JwtTokenLifecycle.RotatedRefreshToken::accessToken).allMatch(t -> !t.isBlank());
        assertThat(results).filteredOn(r -> r.refreshToken() != null).hasSize(1);
        assertThat(results).filteredOn(r -> r.refreshToken() == null).hasSize(1);
        UUID sessionId = lifecycle.requireRefreshToken(original).sessionId();
        assertThat(sessionRevocationRepository.isRevoked(sessionId)).isFalse();
    }

    @Test
    @DisplayName("refresh 로 로그아웃하면 그 refresh 와 직전 refresh(유예 안이어도) 모두 재발급이 거부된다")
    void logoutThenReissueIsRejected() {
        String original = lifecycle.issueTokenPair(principal).refreshToken();
        String current = reissue(original).refreshToken();

        lifecycle.revokeSessionByRefreshToken(current, "USER");
        lifecycle.revokeSessionByRefreshToken(current, "USER"); // 멱등

        assertThatThrownBy(() -> reissue(current)).isInstanceOf(JwtTokenLifecycleException.class);
        assertThatThrownBy(() -> reissue(original)).isInstanceOf(JwtTokenLifecycleException.class);
        UUID sessionId = lifecycle.requireRefreshToken(current).sessionId();
        assertThat(sessionRevocationRepository.isRevoked(sessionId)).isTrue();
    }

    @Test
    @DisplayName("유예 안에 회전이 두 번 일어나도, 처음 refresh 로 늦게 온 요청은 유예로 처리되고 세션은 산다")
    void lateRequestSurvivesTwoRotationsWithinGrace() {
        String original = lifecycle.issueTokenPair(principal).refreshToken();
        String second = reissue(original).refreshToken();
        String third = reissue(second).refreshToken();

        clock.advance(GRACE.minusSeconds(1));
        JwtTokenLifecycle.RotatedRefreshToken late = reissue(original);

        assertThat(late.accessToken()).isNotBlank();
        assertThat(late.refreshToken()).isNull();
        UUID sessionId = lifecycle.requireRefreshToken(original).sessionId();
        assertThat(sessionRevocationRepository.isRevoked(sessionId)).isFalse();
        assertThat(reissue(third).refreshToken()).isNotNull();
    }

    @Test
    @DisplayName("회전 뒤에도 세션 키는 현재 jti 하나라, 유예를 모르는 이전 버전 Pod 의 회전 스크립트도 정상 jti 를 받아들인다")
    void previousVersionRotateScriptAcceptsCurrentJti() {
        String original = lifecycle.issueTokenPair(principal).refreshToken();
        String current = reissue(original).refreshToken();
        JwtTokenLifecycle.RefreshSession session = lifecycle.requireRefreshToken(current);
        String sessionKey = "refresh-token:session:" + session.sessionId();

        assertThat(redisTemplate.opsForValue().get(sessionKey)).isEqualTo(session.tokenId().toString());
        Long result = redisTemplate.execute(
                PREVIOUS_VERSION_ROTATE_SCRIPT,
                List.of(sessionKey),
                session.tokenId().toString(),
                UUID.randomUUID().toString(),
                "60000"
        );
        assertThat(result).isEqualTo(1L);
    }

    @Test
    @DisplayName("다른 role 의 로그아웃(판매자 flow 에 구매자 refresh)은 세션을 건드리지 않는다")
    void logoutWithOtherRoleDoesNotRevoke() {
        String refresh = lifecycle.issueTokenPair(principal).refreshToken();

        lifecycle.revokeSessionByRefreshToken(refresh, "SELLER");

        UUID sessionId = lifecycle.requireRefreshToken(refresh).sessionId();
        assertThat(sessionRevocationRepository.isRevoked(sessionId)).isFalse();
        assertThat(reissue(refresh).refreshToken()).isNotNull();
    }

    private JwtTokenLifecycle.RotatedRefreshToken reissue(String refreshToken) {
        return lifecycle.rotateRefreshToken(lifecycle.requireRefreshToken(refreshToken), principal);
    }

    private static <T> List<T> runConcurrently(int count, java.util.concurrent.Callable<T> task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(count);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private static final class MutableClock extends Clock {

        private volatile Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
