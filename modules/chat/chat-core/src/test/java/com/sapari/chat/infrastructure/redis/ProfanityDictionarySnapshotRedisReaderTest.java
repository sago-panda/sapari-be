package com.sapari.chat.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 받는 쪽의 <b>"버전을 모른다" 판정</b>을 실제 Redis 에서 고정한다.
 *
 * <p>이 클래스에는 테스트가 <b>하나도 없었다.</b> 그 사이에 두 분기가 들어왔고 둘 다 미검증이었다 —
 * 버전 키가 없을 때 본문 머리로 떨어지는 것, 그리고 머리 문법을 발행 쪽과 같은 규칙으로 읽는 것.
 *
 * <p>왜 그 둘이 중요한가. 빈 {@code Mono} 는 호출자에게 <b>"아직 한 번도 발행되지 않았다"</b> 로
 * 읽히고, 그 분기는 부팅 때만 로그를 남긴다(주기 확인은 조용히 지나간다). 그래서 "버전 키만
 * 사라진" 상태를 빈 값으로 돌려주면, 멀쩡한 스냅샷이 Redis 에 있는데도 새로 뜨는 Pod 이 번들로
 * 집행하면서 아무 말도 하지 않는다 — 잃는 것이 시드 이후의 ADMIN 델타 전부다. 그리고 그 상태에
 * 도달하는 경로가 발행 쪽이 안내하는 복구 절차(버전 키 삭제) 자신이었다.
 *
 * <p>목으로는 문법 일치를 재지 못한다. 같은 문자열을 Lua 와 자바가 각자 읽는 것이 이 축의 결함
 * 이었고, 목은 "자바가 무엇을 읽는가" 만 답한다.
 */
@Testcontainers
@DisplayName("사전 스냅샷 읽기 — '버전을 모른다' 가 두 키를 다 본 뒤에만 나온다")
class ProfanityDictionarySnapshotRedisReaderTest {

    /** 반응형 종결자 상한 — 없으면 회귀가 테스트를 실패시키는 대신 <b>멈추게</b> 한다. */
    private static final Duration CEILING = Duration.ofSeconds(10);

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.4").withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static ReactiveStringRedisTemplate redisTemplate;
    private static ProfanityDictionarySnapshotRedisReader reader;

    private static final String SNAPSHOT = "chat:dict:snapshot";
    private static final String VERSION = "chat:dict:version";

    @BeforeAll
    static void startTemplate() {
        connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getFirstMappedPort());
        connectionFactory.afterPropertiesSet();
        redisTemplate = new ReactiveStringRedisTemplate(connectionFactory);
        reader = new ProfanityDictionarySnapshotRedisReader(redisTemplate);
    }

    @AfterAll
    static void stopTemplate() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void clean() {
        redisTemplate.delete(SNAPSHOT, VERSION).block(CEILING);
    }

    private static void set(String key, String value) {
        redisTemplate.opsForValue().set(key, value).block(CEILING);
    }

    private static String body(long version) {
        return version + "\n1\n0\n시발\n";
    }

    @Test
    @DisplayName("두 키가 다 없으면 비었다 — 그때만 '한 번도 발행되지 않았다' 다")
    void bothKeysAbsentMeansNeverPublished() {
        assertThat(reader.readVersion().blockOptional(CEILING)).isEmpty();
    }

    @Test
    @DisplayName("⭐ 버전 키만 사라져도 본문 머리로 버전을 읽는다 — 빈 값이면 조용히 번들로 집행한다")
    void aMissingVersionKeyFallsBackToTheSnapshotHead() {
        set(SNAPSHOT, body(7));

        assertThat(reader.readVersion().blockOptional(CEILING))
                .as("빈 값은 '아직 없음' 으로 읽히고 그 분기는 주기 확인에서 조용하다 — 멀쩡한 "
                        + "스냅샷이 있는데 새 Pod 이 번들로 뜬다")
                .contains(7L);
    }

    @Test
    @DisplayName("버전 키가 더 크면 그것을 쓴다")
    void theHigherVersionKeyWins() {
        set(VERSION, "9");
        set(SNAPSHOT, body(7));

        assertThat(reader.readVersion().blockOptional(CEILING)).contains(9L);
    }

    /**
     * ⭐ <b>두 키의 큰 쪽을 집는다 — 발행 쪽과 같은 규칙이어야 한다.</b>
     *
     * <p>한때 "버전 키가 읽히면 그것, 비면 본문 머리로 폴백" 이었다. 발행 쪽은 같은 두 키를
     * {@code max} 로 집고 근거를 "어느 한 키의 유실이 번호를 내리지 못한다" 로 적는데, 그 근거가
     * 받는 쪽에는 적용되지 않는 <b>비대칭</b>이었다. 비대칭이 드러나는 칸은 <b>버전 키가 읽히면서
     * 본문 머리보다 낮은</b> 하나뿐이다 — 그 칸이 없어서 리더 테스트 다섯 건이 비대칭을 그대로
     * 통과시켰다(되돌림 실측: 5건 0실패).
     *
     * <p>그 상태에서 두 쪽 판정이 갈리면 전 Pod 이 분마다 스냅샷을 다시 읽고 아무것도 교체하지
     * 않으며 로그도 0줄이 된다. 인밴드 도달 경로는 못 찾았다(두 키를 한 스크립트가 같은 수로
     * 쓴다) — 맞추는 이유는 그 불변식이 <b>스크립트 밖 쓰기</b>에 대해 보장되지 않고, 두 쪽이
     * 같은 규칙이면 그 축을 생각할 필요가 없어지기 때문이다.
     */
    @Test
    @DisplayName("⭐ 버전 키가 머리보다 낮으면 머리를 쓴다 — 발행 쪽 max 와 같은 규칙이다")
    void theSnapshotHeadWinsWhenTheVersionKeyIsLower() {
        set(VERSION, "3");
        set(SNAPSHOT, body(7));

        assertThat(reader.readVersion().blockOptional(CEILING))
                .as("발행 쪽은 max 로 집는다 — 받는 쪽이 낮은 쪽을 쓰면 두 판정이 갈린다")
                .contains(7L);
    }

    /**
     * ⭐ <b>머리 문법이 발행 쪽과 같다.</b>
     *
     * <p>한때 이쪽만 {@code Long.parseLong(head.strip())} 이었다. 그러면 {@code " 12\nbody"} 를
     * 12 로 읽는데 발행 스크립트는 0 으로 읽는다 — 같은 값을 보고 두 수가 나오는 상태다. 그
     * 갈림의 대가는 발행 쪽에서 더 컸지만(조건이 평시에도 어긋나 재시도만 소진) 받는 쪽에서도
     * 버전 비교가 틀어진다.
     *
     * <p>문법: 숫자 1~{@code MAX_VERSION_DIGITS}자로 시작하고 그 뒤는 끝이거나 개행. 그 밖은
     * "모른다" 다. 자리수는 상수가 갖는다 — 한때 이 설명문이 18 을 가리키는데 상수는 15 였다.
     */
    @Test
    @DisplayName("⭐ 머리 문법이 발행 쪽과 같다 — 갈리면 같은 값에서 두 수가 나온다")
    void theHeadGrammarMatchesThePublisher() {
        List<String> unreadable = List.of(
                " 12\n1\n0\n시발\n",
                "-5\n1\n0\n시발\n",
                "+7\n1\n0\n시발\n",
                "\t3\n1\n0\n시발\n",
                "12abc\n1\n0\n시발\n",
                "99999999999999999999\n1\n0\n시발\n");

        assertThat(unreadable).hasSize(6);
        for (String payload : unreadable) {
            clean();
            set(SNAPSHOT, payload);
            assertThat(reader.readVersion().blockOptional(CEILING))
                    .as("문법에 안 맞는 머리는 '모른다' 다: %s", payload.replace("\n", "\\n"))
                    .isEmpty();
        }

        clean();
        set(SNAPSHOT, "007\n1\n0\n시발\n");
        assertThat(reader.readVersion().blockOptional(CEILING))
                .as("앞이 0 인 것은 문법 안이다 — 숫자만 있고 뒤가 개행이다")
                .contains(7L);
    }

    @Test
    @DisplayName("본문을 되돌려 준다 — 깨진 본문은 호출자가 교체를 포기하도록 던진다")
    void readDecodesTheBodyAndThrowsOnACorruptOne() {
        set(SNAPSHOT, body(3));
        assertThat(reader.read().block(CEILING).words()).containsExactly("시발");

        set(SNAPSHOT, "3\n2\n0\n시발\n");
        assertThatThrownBy(() -> reader.read().block(CEILING))
                .as("깨진 스냅샷으로 교체하는 것보다 옛 사전으로 집행하는 것이 낫다")
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * ⭐ <b>타입이 오염된 스냅샷 키는 기여하지 않는다 — 분당 WARN 을 만들면 안 된다.</b>
     *
     * <p>{@code max} 로 맞추면서 <b>반쪽만 가져왔다.</b> 발행 쪽은 {@code max} 와 "못 읽은 키는
     * 0 으로 기여" 를 함께 갖는데 리더는 {@code max} 만 가져왔다. 그러면 스냅샷 키가 오염된
     * 동안 {@code readVersion()} 이 <b>매번</b> 에러로 올라가고 {@code RefreshHandler} 의
     * "사전 스냅샷을 읽지 못했다" WARN 이 Pod 당 분당 한 줄씩 나간다 — 전에는 무음이던 상태다.
     * 발행자 javadoc 이 같은 모양을 "읽기 한 줄이 복구 성질을 없앴다" 로 기록해 둔 축이다.
     */
    @Test
    @DisplayName("⭐ 오염된 스냅샷 키가 버전 판정을 죽이지 않는다 — 버전 키가 그대로 쓰인다")
    void aWrongTypedSnapshotKeyDoesNotKillTheVersionRead() {
        set(VERSION, "5");
        redisTemplate.opsForList().rightPush(SNAPSHOT, "오염").block(CEILING);

        assertThat(reader.readVersion().blockOptional(CEILING))
                .as("여기서 에러로 올리면 오염된 키 하나가 Pod 당 분당 WARN 한 줄을 만든다")
                .contains(5L);
    }

    @Test
    @DisplayName("두 키가 다 오염·부재면 비었다 — 번들로 뜨는 것이 두 경우 모두에 안전하다")
    void bothKeysUnreadableMeansEmpty() {
        redisTemplate.opsForList().rightPush(SNAPSHOT, "오염").block(CEILING);

        assertThat(reader.readVersion().blockOptional(CEILING)).isEmpty();
    }

    /**
     * ⭐ <b>스냅샷 키에 나가는 명령이 {@code GETRANGE} 다 — 이것은 판정이 아니라 비용이다.</b>
     *
     * <p>⚠️ 판정 단언으로는 이 축이 <b>안 잡힌다.</b> {@code GET} 으로 되돌려도 머리만 파싱하니
     * 결과가 같다 — 실측으로 되돌림 변이가 9건 0실패로 통과했다. 그래서 명령 자체를 센다.
     *
     * <p>왜 비용이 성질인가: 그 키가 나르는 것은 <b>본문 전체</b>(번들 기준 11,345 B + ADMIN
     * 델타)이고 이 읽기는 Pod 당 분당 1회 돈다. {@code ProfanityDictionaryRefreshHandler} 가
     * 버전 키를 따로 둔 이유를 "비용은 {@code GET} 하나(몇 바이트)다 — 스냅샷 전체(수십 KB)가
     * 아닌 것이 버전 키를 따로 둔 이유다" 로 적는데, 무조건 경로에서 전체를 당기면 그 이유가
     * 지워진다.
     *
     * ⭐ <b>스냅샷은 머리만 당긴다 — 버전 키를 따로 둔 이유를 무조건 경로에서 지우지 않는다.</b>
     *
     * <p>처음에 {@code GET} 으로 두 키를 읽었다. 그 키가 나르는 것은 <b>본문 전체</b>(번들 기준
     * 11,345 B + ADMIN 델타)인데, {@code ProfanityDictionaryRefreshHandler} 가 버전 키를 따로
     * 둔 이유를 "비용은 {@code GET} 하나(몇 바이트)다 — 스냅샷 전체(수십 KB)가 아닌 것이 버전
     * 키를 따로 둔 이유다" 로 적는다. {@code GETRANGE} 로 바꿔 같은 {@code max} 성질을 몇
     * 바이트로 얻는다.
     *
     * <p>이 테스트는 그 전환이 <b>판정을 안 바꿨다</b>는 쪽을 잰다 — 머리가 상한 자리수면 개행이
     * 같이 들어와 문법 안이고, 상한보다 길면 개행 없이 숫자만 들어와 거부다. 본문이 아무리 커도
     * 같다.
     */
    @Test
    @DisplayName("⭐ 스냅샷 키는 GETRANGE 로 읽는다 — 전체를 당기면 버전 키의 존재 이유가 지워진다")
    void theSnapshotKeyIsReadWithGetrange() {
        set(VERSION, "3");
        set(SNAPSHOT, body(3));
        resetCommandStats();

        reader.readVersion().blockOptional(CEILING);

        String stats = commandStats();
        assertThat(stats)
                .as("머리만 당겨야 한다 — 이 읽기는 Pod 당 분당 1회 돌고 본문은 11 KB 다")
                .contains("cmdstat_getrange");
    }

    private static void resetCommandStats() {
        redisTemplate.getConnectionFactory().getReactiveConnection()
                .serverCommands().resetConfigStats().block(CEILING);
    }

    private static String commandStats() {
        return String.valueOf(redisTemplate.getConnectionFactory().getReactiveConnection()
                .serverCommands().info("commandstats").block(CEILING));
    }

    @Test
    @DisplayName("⭐ 본문이 커도 머리만으로 판정한다 — 상한 자리수와 그 위를 같이 잰다")
    void onlyTheHeadDecidesRegardlessOfBodySize() {
        StringBuilder big = new StringBuilder("999999999999999\n1\n0\n");
        big.append("시발\n");
        for (int i = 0; i < 2000; i++) {
            big.append("욕설").append(i).append('\n');
        }
        set(SNAPSHOT, big.toString());
        assertThat(reader.readVersion().blockOptional(CEILING))
                .as("상한 자리수(15)면 그 뒤가 개행이라 문법 안이다")
                .contains(999_999_999_999_999L);

        clean();
        set(SNAPSHOT, "1000000000000000\n1\n0\n시발\n");
        assertThat(reader.readVersion().blockOptional(CEILING))
                .as("상한보다 길면 머리 범위에 개행이 없어 거부다 — 본문을 안 당겨도 같은 판정이다")
                .isEmpty();
    }
}
