package com.sapari.chat.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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

import com.sapari.chat.domain.repository.ChatBanWriteRepository.MirrorApply;

/**
 * 밴 미러 쓰기가 <b>줄어들지 않는지</b>를 실제 Redis에서 고정한다.
 *
 * <p>이 보장이 필요한 이유는 하나다. 서로 다른 방에서 같은 사람을 동시에 강퇴하면 두 호출자가 각각 밴을
 * 만들고 각자 미러에 쓴다. 무조건 덮어쓰면 <b>나중에 도착한 쪽이 이기므로</b> 짧은 TTL이 남을 수 있고,
 * 집행은 미러가 하므로 그 사람은 정본에 한 달이 남아 있어도 일주일 뒤에 돌아온다.
 *
 * <p><b>도착 순서를 바꿔 가며 확인한다.</b> 정본을 다시 읽어 가장 긴 것을 쓰는 방식도 시도했지만 그건
 * "무엇을 쓸지"만 고치고 "누가 마지막에 쓰는지"는 그대로여서 레이스를 닫지 못했다. 그래서 여기서 재는
 * 것은 값이 아니라 <b>순서 독립</b>이다 — 어느 쪽이 나중에 도착해도 결과가 같아야 한다.
 *
 * <p>순차 호출은 <b>단조성만</b> 잰다 — 쪼갠 구현도 통과한다. 비교와 쓰기가 <b>한 실행 안</b>에 있는지는
 * 같은 키에 동시에 써 봐야 갈리고, 그 축은 {@code concurrentWritesKeepTheLongest}가 잰다.
 */
@Testcontainers
@DisplayName("ChatBanWriteRepository — 미러는 늘어나기만 한다")
class ChatBanWriteRedisRepositoryTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.4").withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static ChatBanWriteRedisRepository repository;

    private static final Instant NOW = Instant.parse("2026-09-05T00:00:00Z");
    private static final Duration WEEK = Duration.ofDays(7);
    private static final Duration MONTH = Duration.ofDays(30);

    /** 정본 잠금이 집어 주는 번호의 대역. 순서대로 부르면 커밋 순서대로 집은 것과 같다. */
    private static final AtomicLong VERSIONS = new AtomicLong();

    private UUID userId;

    @BeforeAll
    static void startTemplate() {
        connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getFirstMappedPort());
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        repository = new ChatBanWriteRedisRepository(redisTemplate);
    }

    @AfterAll
    static void stopTemplate() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void freshUser() {
        userId = UUID.randomUUID();
    }

    private String key() {
        return "chat:banned:" + userId;
    }

    private static long nextVersion() {
        return VERSIONS.incrementAndGet();
    }

    private MirrorApply ban(Duration length) {
        return repository.ban(userId, NOW.plus(length), NOW, nextVersion());
    }

    private MirrorApply banForever() {
        return repository.ban(userId, null, NOW, nextVersion());
    }

    /** 남은 TTL. 만료 없음은 -1, 키 없음은 -2 — Redis의 규약을 그대로 쓴다. */
    private long ttlMillis() {
        Long ttl = redisTemplate.getExpire(key(), java.util.concurrent.TimeUnit.MILLISECONDS);
        return ttl == null ? -2 : ttl;
    }

    @Test
    @DisplayName("없던 키에 밴을 걸면 그 만료가 붙는다")
    void firstBanSetsTheExpiry() {
        // when
        ban(WEEK);

        // then
        assertThat(ttlMillis()).isCloseTo(WEEK.toMillis(), org.assertj.core.data.Offset.offset(2_000L));
    }

    @Test
    @DisplayName("⭐ 긴 밴 뒤에 짧은 밴이 와도 줄어들지 않는다 — 동시 강퇴에서 늦게 도착하는 쪽이 짧을 수 있다")
    void shorterBanDoesNotShortenAnExistingOne() {
        // given: 한 달짜리가 먼저 자리를 잡았다
        ban(MONTH);

        // when: 일주일짜리가 뒤에 도착한다
        ban(WEEK);

        // then: 한 달이 그대로 남는다. 줄어들면 그 사용자는 23일 일찍 돌아온다
        assertThat(ttlMillis())
                .as("짧은 밴이 긴 밴을 덮었다 — 미러가 정본보다 일찍 풀린다")
                .isGreaterThan(MONTH.toMillis() - 2_000L);
    }

    /**
     * 위 테스트의 <b>대조군</b>이다 — 이 순서는 무조건 덮어쓰는 구현에서도 통과한다.
     * 둘을 함께 두는 이유는 "어느 쪽이 먼저 와도 같다"를 눈으로 보이게 하기 위해서고,
     * 실제로 판별하는 것은 긴 것 뒤에 짧은 것이 오는 쪽이다.
     */
    @Test
    @DisplayName("짧은 것 먼저 와도 결과가 같다 (대조군 — 이 순서만으로는 판별되지 않는다)")
    void longerBanAppliesOverAShorterOne() {
        // given & when: 짧은 것 먼저, 긴 것 나중
        ban(WEEK);
        ban(MONTH);

        // then
        assertThat(ttlMillis()).isGreaterThan(MONTH.toMillis() - 2_000L);
    }

    /**
     * ⭐ <b>비교와 쓰기가 한 실행 안에 있는가.</b>
     *
     * <p>위 테스트들은 순차 호출이라 <b>단조성만</b> 잰다 — 같은 의미를 두 라운드트립(`PTTL` 후 조건부
     * `SET`)으로 쪼개도 전부 통과한다. 그러면 "Lua를 걷어내고 자바에서 단순화"하는 변경이 초록으로
     * 지나가고, 그 순간 이번에 닫은 레이스가 말없이 다시 열린다.
     *
     * <p><b>요점은 겹침이 끝난 <i>직후</i>에 검사하는 것이다.</b> 쪼갠 구현에서도 짧은 값이 시작 창에서
     * 이겼다가 뒤이은 긴 라이터가 다시 올려 주므로, <b>모두 끝난 뒤에 한 번만 보면 구조적으로 판별되지
     * 않는다</b> — 키가 이미 최댓값으로 치유된 뒤다. 실제로 그렇게 짰다가 두 번 통과시켰다.
     *
     * <p>라운드마다 새 키를 쓰는 것은 겹침 구간을 반복해서 만들기 위해서고, 한 키를 두들겨도 라운드마다
     * 검사하면 잡힌다(리뷰어 실측). <b>검사 시점이 결정 변수이고 키 재사용은 부차적이다.</b>
     */
    @Test
    @DisplayName("⭐ 동시에 써도 가장 긴 것이 남는다 — 비교와 쓰기가 한 실행 안에 있어야 성립한다")
    void concurrentWritesKeepTheLongest() throws Exception {
        // given
        int rounds = 60;
        int writersPerRound = 8;
        Duration longest = Duration.ofDays(365);
        Duration shortest = Duration.ofHours(12);

        ExecutorService pool = Executors.newFixedThreadPool(writersPerRound);
        try {
            for (int round = 0; round < rounds; round++) {
                // 라운드마다 새 키 — 겹침이 실제로 일어나는 시작 구간을 반복해서 만든다
                userId = UUID.randomUUID();
                CountDownLatch start = new CountDownLatch(1);
                List<Future<?>> writers = new ArrayList<>();
                for (int w = 0; w < writersPerRound; w++) {
                    // 절반은 가장 긴 것, 절반은 가장 짧은 것 — 짧은 쪽이 이기면 최댓값이 깨진다
                    Duration length = (w % 2 == 0) ? longest : shortest;
                    // 긴 쪽이 더 큰 번호다 — 정본이 늘리기 전용이라 잠금 안에서 나중에 집은 번호가 더
                    // 짧은 밴을 들 수 없다. 도착 순서는 섞인다: 짧은 쪽이 늦게 오면 번호로, 긴 쪽이
                    // 늦게 오면 만료 비교로 결과가 같아야 한다.
                    long version = nextVersion() + (w % 2 == 0 ? writersPerRound : 0);
                    UUID target = userId;
                    writers.add(pool.submit(() -> {
                        if (!start.await(30, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("시작 래치가 30초 안에 열리지 않았다 — 주 스레드가 countDown 전에 터졌다. 상한이 없으면 이 스레드가 영원히 블록된 채 남아 같은 JVM 의 뒤따르는 테스트를 오염시킨다");
                        }
                        repository.ban(target, NOW.plus(length), NOW, version);
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> f : writers) {
                    f.get(30, TimeUnit.SECONDS);
                }

                // then: 읽기와 쓰기 사이에 남이 끼어들면 이 라운드에서 최댓값이 깨진다
                assertThat(ttlMillis())
                        .as("%d번째 라운드에서 짧은 쪽이 이겼다 — 비교와 쓰기가 쪼개져 있다", round)
                        .isGreaterThan(longest.toMillis() - 5_000L);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("⭐ 영구 밴은 어떤 기한부 밴도 덮지 못한다 — 만료 없음이 가장 긴 만료다")
    void permanentBanIsNeverOverwritten() {
        // given
        banForever();

        // when
        ban(MONTH);

        // then: -1은 만료 없음이다
        assertThat(ttlMillis()).isEqualTo(-1);
    }

    @Test
    @DisplayName("기한부 밴은 영구로 승격된다 — 위로 가는 방향은 열려 있다")
    void timedBanCanBePromotedToPermanent() {
        // given
        ban(WEEK);

        // when
        banForever();

        // then
        assertThat(ttlMillis()).isEqualTo(-1);
    }

    @Test
    @DisplayName("이미 지난 만료는 아무것도 쓰지 않는다 — 음수 TTL은 거부되고, 정본에 없는 상태다")
    void expiredBanWritesNothing() {
        // when: 만료가 현재보다 과거다
        repository.ban(userId, NOW.minus(Duration.ofDays(1)), NOW, nextVersion());

        // then: 키 자체가 생기지 않는다
        assertThat(redisTemplate.hasKey(key())).isFalse();
    }

    @Test
    @DisplayName("이미 지난 만료가 기존 밴을 지우지도 않는다")
    void expiredBanDoesNotClearAnExistingOne() {
        // given
        ban(MONTH);

        // when
        repository.ban(userId, NOW.minus(Duration.ofDays(1)), NOW, nextVersion());

        // then
        assertThat(ttlMillis()).isGreaterThan(MONTH.toMillis() - 2_000L);
    }

    /**
     * 해제가 쓰기가 아니라 <b>삭제</b>여야 하는 이유를 그대로 잰다.
     *
     * <p>영구 밴은 TTL 이 없다. 어댑터의 {@code VERSIONED_EXTEND_ONLY} 스크립트는 {@code PTTL == -1}(만료 없음)에서
     * 즉시 0 을 돌려주므로, 어떤 값을 넘겨도 영구 밴은 <b>줄어들지도 사라지지도 않는다</b>.
     * 그래서 해제는 이 경로를 타면 안 되고, 그 사실이 이 테스트의 내용이다.
     */
    @Test
    @DisplayName("⭐ 영구 밴(TTL 없음)도 해제로 지워진다 — 늘리기 전용 쓰기로는 못 지운다")
    void releaseMirrorRemovesAPermanentBan() {
        // given: 만료 없는 영구 밴
        repository.ban(userId, null, NOW, nextVersion());
        assertThat(redisTemplate.hasKey(key())).isTrue();
        assertThat(ttlMillis())
                .as("영구 밴은 TTL 이 없어야 한다 — 그래서 스스로 풀리지 않는다")
                .isNegative();

        // when
        repository.releaseMirror(userId, nextVersion());

        // then
        assertThat(redisTemplate.hasKey(key()))
                .as("미러가 남으면 아무도 풀리지 않는다 — 집행은 이 키가 한다")
                .isFalse();
    }

    @Test
    @DisplayName("⭐ 없는 키를 지워도 조용히 성공한다 — 해제 명령의 멱등성이 이 성질에 걸려 있다")
    void releaseMirrorIsANoOpWhenNothingIsThere() {
        // given: 키가 없다(정본은 지워졌고 미러 삭제만 재시도하는 상태)
        assertThat(redisTemplate.hasKey(key())).isFalse();

        // when & then: 여기서 던지면 재시도 경로가 끊긴다
        repository.releaseMirror(userId, nextVersion());
        assertThat(redisTemplate.hasKey(key())).isFalse();
    }

    @Test
    @DisplayName("기한부 밴도 해제로 즉시 지워진다 — 만료를 기다리지 않는다")
    void releaseMirrorRemovesATimedBan() {
        // given
        ban(MONTH);

        // when
        repository.releaseMirror(userId, nextVersion());

        // then: 오판정 하나가 최소 7일·최대 1년이던 것이 이 삭제로 끝난다
        assertThat(redisTemplate.hasKey(key())).isFalse();
    }

    /**
     * 해제와 적용이 엇갈리는 경쟁 — 늘리기 전용만으로는 못 막던 것이다.
     *
     * <p>순서: 강퇴가 잠금 안에서 번호 v1 을 집고 커밋 → 해제가 v2 를 집고 커밋 → 해제의 삭제가 먼저
     * 미러에 닿음 → 강퇴의 쓰기가 늦게 닿음. 번호가 없으면 키가 없으므로({@code PTTL = -2}) 늦은 쓰기가
     * 무조건 이기고, 영구 밴이면 스스로 사라지지도 않는다.
     */
    @Test
    @DisplayName("⭐ 해제 뒤에 늦게 온 적용은 거절된다 — 풀린 영구 밴이 미러에 되살아나지 않는다")
    void aStaleApplyAfterReleaseIsRejected() {
        // given: 강퇴가 집은 번호와 해제가 집은 번호. 해제가 나중에 잠금을 지났다
        long kickVersion = nextVersion();
        long releaseVersion = nextVersion();
        assertThat(repository.releaseMirror(userId, releaseVersion)).isTrue();

        // when: 강퇴의 쓰기가 늦게 도착한다
        MirrorApply result = repository.ban(userId, null, NOW, kickVersion);

        // then
        assertThat(result).isEqualTo(MirrorApply.STALE);
        assertThat(redisTemplate.hasKey(key()))
                .as("정본에 밴이 없는데 미러에 생기면 영구 과다 차단이다 — TTL 이 없어 스스로 안 풀린다")
                .isFalse();
    }

    @Test
    @DisplayName("⭐ 새 밴 뒤에 늦게 온 해제 삭제는 거절된다 — 해제 다음에 걸린 밴을 지우지 않는다")
    void aStaleReleaseAfterANewerBanIsRejected() {
        // given: 해제가 v1, 그 뒤 새 강퇴가 v2 로 밴을 걸었고 그 쓰기가 먼저 닿았다
        long releaseVersion = nextVersion();
        long banVersion = nextVersion();
        assertThat(repository.ban(userId, NOW.plus(WEEK), NOW, banVersion)).isEqualTo(MirrorApply.CREATED);

        // when: 해제의 삭제가 늦게 도착한다
        boolean cleared = repository.releaseMirror(userId, releaseVersion);

        // then
        assertThat(cleared).isFalse();
        assertThat(redisTemplate.hasKey(key())).as("정본에 있는 밴이 미러에서 사라지면 집행되지 않는다").isTrue();
    }

    @Test
    @DisplayName("해제 뒤에 새로 걸린 밴은 더 큰 번호라 그대로 쓰인다 — 거절은 늦은 것만 겨눈다")
    void aNewerBanAfterReleaseIsApplied() {
        repository.releaseMirror(userId, nextVersion());

        assertThat(ban(WEEK)).isEqualTo(MirrorApply.CREATED);
        assertThat(redisTemplate.hasKey(key())).isTrue();
    }

    @Test
    @DisplayName("같은 번호를 두 번 쓰면 두 번째는 거절된다 — 번호는 '본 것보다 큰가' 만 묻는다")
    void theSameVersionTwiceIsStale() {
        long version = nextVersion();
        assertThat(repository.ban(userId, NOW.plus(WEEK), NOW, version)).isEqualTo(MirrorApply.CREATED);

        assertThat(repository.ban(userId, NOW.plus(MONTH), NOW, version)).isEqualTo(MirrorApply.STALE);
        assertThat(ttlMillis()).isLessThanOrEqualTo(WEEK.toMillis());
    }

    /** 재적재가 "미러에 없던 밴" 을 세는 근거가 이 구분이다. */
    @Test
    @DisplayName("결과가 새로 씀·연장·무변화를 가른다 — 재적재는 새로 쓴 수를 어긋났던 수로 센다")
    void theResultTellsCreatedFromExtendedFromUnchanged() {
        assertThat(ban(WEEK)).isEqualTo(MirrorApply.CREATED);
        assertThat(ban(MONTH)).isEqualTo(MirrorApply.EXTENDED);
        assertThat(ban(WEEK)).isEqualTo(MirrorApply.UNCHANGED);
        assertThat(banForever()).isEqualTo(MirrorApply.EXTENDED);
        assertThat(ban(MONTH)).isEqualTo(MirrorApply.UNCHANGED);
    }

    @Test
    @DisplayName("번호 키에는 수명이 있다 — 밴마다 영원히 남는 키를 만들지 않는다")
    void theVersionKeyExpires() {
        banForever();

        Long ttl = redisTemplate.getExpire("chat:ban:ver:" + userId, TimeUnit.MILLISECONDS);
        assertThat(ttl).isPositive().isLessThanOrEqualTo(ChatBanWriteRedisRepository.VERSION_TTL.toMillis());
    }
}
