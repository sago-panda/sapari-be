package com.sapari.chat.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.sapari.chat.application.port.ProfanityDictionarySnapshot;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

/**
 * 사전 발행의 <b>세 효과가 갈라지지 않는지</b>를 실제 Redis에서 고정한다.
 *
 * <p>버전 증가 · 본문 쓰기 · 알림 발행이 한 스크립트다. 나누면 나누는 방식마다 다른 고장이
 * 생긴다 — 버전만 오르고 본문이 안 써지면 받는 쪽의 주기 확인이 "새 버전이 있다" 를 보고 옛
 * 본문을 읽어 <b>버전만 올려 둔다</b>(그 뒤로 진짜 변경이 와도 같은 번호면 건너뛴다).
 * 본문만 써지고 알림이 안 가면 전파가 주기 간격만큼 늦는다.
 *
 * <p>목으로는 이것을 재지 못한다. Lua 안에서 {@code PUBLISH} 가 실제로 나가는지, 그 안의
 * {@code string.match} 가 자바와 같은 수를 읽는지는 Redis 의 동작이고 스크립트 문자열을
 * 단언하는 것으로는 같은 답이 아니다 — 문자열이 맞아도 그 명령이 그 서버에서 거부될 수 있고
 * (실측으로 Redis 7.4.12 에서 허용된다), 실제로 이 스크립트는 자바 텍스트 블록의 이스케이프
 * 하나 때문에 <b>컴파일 자체가 거부된</b> 적이 있다({@code unfinished string}). 그때 이 클래스의
 * 11건이 전부 빨개져서 알았다.
 */
@Testcontainers
@DisplayName("사전 스냅샷 발행 — 버전·본문·알림이 한 번에 나간다")
class ProfanityDictionarySnapshotRedisPublisherTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.4").withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static ProfanityDictionarySnapshotRedisPublisher publisher;

    private static final String SNAPSHOT = "chat:dict:snapshot";
    private static final String VERSION = "chat:dict:version";
    private static final String CHANNEL = "chat:dict:changed";

    @BeforeAll
    static void startTemplate() {
        connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getFirstMappedPort());
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        publisher = new ProfanityDictionarySnapshotRedisPublisher(redisTemplate);
    }

    @AfterAll
    static void stopTemplate() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void clean() {
        redisTemplate.delete(List.of(SNAPSHOT, VERSION));
    }

    private static Set<String> ordered(String... values) {
        return new LinkedHashSet<>(List.of(values));
    }

    /** 경합이 없는 평시 경로 — 지금 버전을 집어 그대로 조건으로 넘긴다. */
    private static long publish(Set<String> words, Set<String> whitelist) {
        return publisher.publishIfUnchanged(publisher.currentVersion(), words, whitelist)
                .orElseThrow(() -> new AssertionError("경합이 없는데 조건이 깨졌다"));
    }

    @Test
    @DisplayName("⭐ 한 번의 발행이 버전·본문·알림을 모두 남긴다")
    void onePublishLeavesAllThreeEffects() throws Exception {
        // given: 알림을 받을 구독자. 발행이 먼저 나가면 Pub/Sub 은 비영속이라 영영 못 받으므로
        // 구독이 자리를 잡은 뒤에 발행한다.
        BlockingQueue<String> received = new ArrayBlockingQueue<>(4);
        RedisMessageListenerContainer listener = new RedisMessageListenerContainer();
        listener.setConnectionFactory(connectionFactory);
        listener.addMessageListener(
                (message, pattern) -> received.offer(new String(message.getBody())),
                new ChannelTopic(CHANNEL));
        listener.afterPropertiesSet();
        listener.start();
        try {
            // when
            long version = publish(ordered("시발", "개새끼"), ordered("시발점"));

            // then: 버전 키
            assertThat(version).isEqualTo(1);
            assertThat(redisTemplate.opsForValue().get(VERSION)).isEqualTo("1");

            // then: 본문 — 코덱이 되읽을 수 있고 버전이 머리에 박혀 있다
            ProfanityDictionarySnapshot decoded = ProfanityDictionarySnapshotCodec.decode(
                    redisTemplate.opsForValue().get(SNAPSHOT));
            assertThat(decoded.version()).isEqualTo(1);
            assertThat(decoded.words()).containsExactly("시발", "개새끼");
            assertThat(decoded.whitelist()).containsExactly("시발점");

            // then: 알림 — 본문은 버전 하나다. 사전 내용을 실으면 놓친 Pod 이 영구히
            // 다른 사전으로 집행한다(Pub/Sub 비영속).
            assertThat(received.poll(5, TimeUnit.SECONDS))
                    .as("알림이 안 가면 전파가 주기 확인 간격만큼 늦는다")
                    .isEqualTo("1");
        } finally {
            listener.stop();
            listener.destroy();
        }
    }

    /**
     * ⭐ 버전이 단조인 것은 <b>클라이언트가 번호를 정하지 않기</b> 때문이다.
     *
     * <p>받는 쪽은 "읽은 버전이 지금 것보다 클 때만" 교체한다. 번호를 호출자가 정하면 live-app
     * Pod 가 둘일 때 같은 번호를 두 번 쓰거나 뒤로 갈 수 있고, 그러면 그 비교가 거짓을 지킨다 —
     * 더 새 사전을 더 낮은 번호 때문에 거부하게 된다.
     */
    @Test
    @DisplayName("⭐ 버전은 발행마다 오른다 — 번호를 Redis 가 정한다")
    void theVersionAdvancesOnEveryPublish() {
        assertThat(publish(ordered("시발"), Set.of())).isEqualTo(1);
        assertThat(publish(ordered("시발", "지랄"), Set.of())).isEqualTo(2);
        assertThat(publish(ordered("시발"), Set.of())).isEqualTo(3);

        // 본문의 버전도 같이 올라간다 — 두 값이 어긋나 보이면 주기 확인이 틀린 판단을 한다.
        assertThat(ProfanityDictionarySnapshotCodec.decode(
                redisTemplate.opsForValue().get(SNAPSHOT)).version()).isEqualTo(3);
    }

    /**
     * {@code SET} 이라 이전 값의 타입을 가리지 않고 덮는다 — 누군가 이 키를 다른 타입으로 써
     * 놨어도 다음 발행이 고친다. 강퇴 명단과 다른 점이고({@code SADD} 는 오염된 키에 들어가지
     * 못해 복구 경로가 따로 필요했다) 그래서 여기엔 재적재 엔드포인트가 없다.
     */
    @Test
    @DisplayName("오염된 키도 발행이 덮어 고친다 — 재적재 경로가 필요 없는 이유다")
    void aCorruptedKeyIsOverwritten() {
        redisTemplate.opsForSet().add(SNAPSHOT, "이것은-집합이다");

        publish(ordered("시발"), Set.of());

        assertThat(ProfanityDictionarySnapshotCodec.decode(
                redisTemplate.opsForValue().get(SNAPSHOT)).words()).containsExactly("시발");
    }

    /**
     * ⭐ <b>이 테스트가 lost update 를 막는다.</b>
     *
     * <p>본문은 호출자가 정본에서 읽은 값이고 그 읽기는 발행 <i>밖</i>에서 일어난다. 조건이
     * 없으면 이 끼어들기가 성립한다:
     * <pre>
     *   A: 정본 읽어 {시발} 손에 듦 (버전 0 을 집어 뒀다)
     *   B:              {시발,지랄} 읽고 발행 → v1
     *   A: 발행 → v2, 본문 {시발}      ← v2 가 최신이라 함대가 지랄 없는 사전을 집행한다
     * </pre>
     * 지랄은 테이블에 있어 관리자 목록에 보이는데 어느 Pod 도 막지 않고, 발행은 쓰기가 있을
     * 때만이라 다음 쓰기나 부팅까지 어긋난 채로 남는다.
     *
     * <p>조건이 서면 A 의 쓰기가 거부되고 <b>아무것도 쓰이지 않는다</b> — B 의 본문이 그대로
     * 남고 버전도 전진하지 않는다. 호출자가 정본을 다시 읽어 재시도한다.
     */
    @Test
    @DisplayName("⭐ 집어 둔 버전이 밀렸으면 쓰지 않는다 — 낡은 본문이 새 본문을 덮지 못한다")
    void aStaleExpectedVersionIsRefusedAndWritesNothing() {
        // given: A 가 버전 0 을 집고 {시발} 을 손에 들었다
        long expectedByA = publisher.currentVersion();
        assertThat(expectedByA).isZero();

        // B 가 먼저 발행한다 — 더 새 본문이다
        long versionByB = publish(ordered("시발", "지랄"), Set.of());
        assertThat(versionByB).isEqualTo(1);

        // when: A 가 낡은 본문으로 들어온다
        OptionalLong refused =
                publisher.publishIfUnchanged(expectedByA, ordered("시발"), Set.of());

        // then: 거부되고 아무것도 안 바뀐다
        assertThat(refused).isEmpty();
        assertThat(redisTemplate.opsForValue().get(VERSION))
                .as("실패한 시도가 번호를 태우면 받는 쪽이 매 주기마다 헛읽기를 한다")
                .isEqualTo("1");
        ProfanityDictionarySnapshot survived = ProfanityDictionarySnapshotCodec.decode(
                redisTemplate.opsForValue().get(SNAPSHOT));
        assertThat(survived.version()).isEqualTo(1);
        assertThat(survived.words())
                .as("B 의 본문이 그대로 남아야 한다 — 지랄이 사라지면 그게 lost update 다")
                .containsExactly("시발", "지랄");
    }

    /**
     * ⭐ <b>ABA — 번호 재사용을 막는다.</b>
     *
     * <p>기준을 버전 키만으로 잡으면 {@code GET} 이 없는 키에 {@code false} 를 주므로
     * "한 번도 발행 안 됨" 과 "버전 키가 사라짐" 이 <b>같은 토큰(0)</b>이 된다. 토큰이 유일하지
     * 않으면 조건이 지키는 것이 없다 — 낡은 발행자가 통과하면서 <b>번호를 재사용</b>하고 더 새
     * 본문을 덮는다. 그리고 성공을 돌려주므로 재시도도 없고, 그 번호를 이미 적용한 Pod 은
     * 버전이 같아 다시 읽지 않는다.
     *
     * <p>도달 경로가 {@code allkeys-lru} 축출과 <b>이 어댑터가 안내하는 복구 절차</b>(버전 키
     * 삭제) 자신이었다.
     */
    @Test
    @DisplayName("⭐ 버전 키가 사라져도 낡은 발행자를 거부한다 — 번호 재사용(ABA)을 막는다")
    void aStalePublisherIsRefusedEvenWhenTheVersionKeyIsGone() {
        // given: A 가 0 을 집어 뒀고, 그 사이 B 가 더 새 본문으로 발행했다
        long expectedByA = publisher.currentVersion();
        assertThat(expectedByA).isZero();
        assertThat(publish(ordered("시발", "지랄"), Set.of())).isEqualTo(1);

        // 버전 키만 사라진다 — 본문은 남아 있다
        redisTemplate.delete(VERSION);

        // when: A 가 낡은 본문으로 들어온다
        OptionalLong refused =
                publisher.publishIfUnchanged(expectedByA, ordered("시발"), Set.of());

        // then: 거부된다 — 기준이 본문 머리로 떨어지므로 토큰이 0 이 아니다
        assertThat(refused)
                .as("여기서 통과하면 v1 이 재사용되고 지랄이 함대에서 사라진다")
                .isEmpty();
        assertThat(ProfanityDictionarySnapshotCodec.decode(
                redisTemplate.opsForValue().get(SNAPSHOT)).words())
                .containsExactly("시발", "지랄");
    }

    @Test
    @DisplayName("⭐ 본문이 사라져도 버전 키가 남아 있으면 낡은 발행자를 거부한다")
    void aStalePublisherIsRefusedWhenOnlyTheBodyIsGone() {
        long expectedByA = publisher.currentVersion();
        assertThat(publish(ordered("시발", "지랄"), Set.of())).isEqualTo(1);

        redisTemplate.delete(SNAPSHOT);

        assertThat(publisher.publishIfUnchanged(expectedByA, ordered("시발"), Set.of()))
                .as("두 키 중 남은 쪽이 토큰을 떨어뜨리지 않아야 한다")
                .isEmpty();
        // ⚠️ 이 벡터는 ABA 수정을 되돌려도 <통과한다> — 되돌린 구현은 버전 키만 보고 그 키가
        // 남아 있으므로 토큰이 안 떨어진다. 즉 이것은 그 수정의 회귀 가드가 아니다. 막는 것은
        // 장래의 "머리 전용" 구현이다(본문 머리만 기준으로 삼으면 본문이 사라진 이 상태에서
        // 토큰이 0 으로 떨어져 낡은 발행자가 통과한다). 회귀 가드는 짝인
        // aStalePublisherIsRefusedWhenTheVersionKeyIsGone 쪽이다.
    }

    @Test
    @DisplayName("한 번도 발행되지 않았으면 현재 버전이 0 이다 — 스크립트도 없는 키를 0 으로 읽는다")
    void theCurrentVersionIsZeroBeforeAnyPublish() {
        assertThat(publisher.currentVersion()).isZero();

        // 그 0 을 조건으로 넘기면 첫 발행이 성립한다 — 둘이 같은 기준을 써야 한다
        assertThat(publisher.publishIfUnchanged(0L, ordered("시발"), Set.of()))
                .hasValue(1L);
    }

    /**
     * ⭐ <b>버전 키가 숫자가 아니어도 발행이 덮어 고친다.</b>
     *
     * <p>이 테스트는 전에 <b>반대를 못 박고 있었다</b> — "숫자가 아니면 던진다(조건 비교가
     * 영원히 어긋나는 것보다 낫다)". 그 근거가 틀렸다: 스크립트는 그 키를 0 으로 읽고
     * {@code SET} 으로 덮으므로 <b>조건이 어긋나지 않고 발행이 성립한다.</b> 던지면 그
     * 스크립트가 애초에 돌지 못해, 값 하나로 모든 ADMIN 쓰기와 부팅 발행이 500 이 되고 복구가
     * 수동 {@code DEL} 뿐이었다. 스냅샷 키 쪽은 같은 상황에서 0 으로 떨어뜨려 치유를 살려
     * 뒀는데 두 키가 갈려 있었다.
     *
     * <p>조용히 넘기지도 않는다 — 못 읽은 키는 WARN 이고, 그 로그에 값은 싣지 않는다(신뢰경계
     * 밖 문자열이라 개행 삽입으로 로그를 위조할 수 있다).
     */
    @Test
    @DisplayName("⭐ 버전 키가 숫자가 아니어도 발행이 덮어 고친다 — 던지면 복구가 수동 DEL 뿐이다")
    void aNonNumericVersionKeyIsHealedByThePublish() {
        redisTemplate.opsForValue().set(VERSION, "오염된값");

        assertThat(publisher.currentVersion())
                .as("읽을 수 없는 키는 조건 비교에 기여하지 않는다")
                .isZero();
        assertThat(publisher.publishIfUnchanged(0L, ordered("시발"), Set.of()))
                .as("여기서 던지면 값 하나로 전 경로가 500 이 된다")
                .hasValue(1L);
        assertThat(redisTemplate.opsForValue().get(VERSION)).isEqualTo("1");
    }

    @Test
    @DisplayName("1,078 항목도 한 번의 발행으로 나간다 — 청크가 필요 없다")
    void aFullSizedDictionaryPublishesInOneRoundTrip() {
        Set<String> words = new LinkedHashSet<>();
        for (int i = 0; i < 1078; i++) {
            words.add("욕설" + i);
        }
        Set<String> whitelist = new LinkedHashSet<>();
        for (int i = 0; i < 38; i++) {
            whitelist.add("정상어" + i);
        }

        publish(words, whitelist);

        // SADD 와 달리 SET 은 인자 하나라 Lua 스택 한계(unpack ~8,000)에 닿지 않는다 —
        // 강퇴 재적재가 청크를 써야 했던 이유가 여기엔 없다.
        ProfanityDictionarySnapshot decoded = ProfanityDictionarySnapshotCodec.decode(
                redisTemplate.opsForValue().get(SNAPSHOT));
        assertThat(decoded.words()).hasSize(1078).containsExactlyElementsOf(words);
        assertThat(decoded.whitelist()).hasSize(38).containsExactlyElementsOf(whitelist);
    }

    /**
     * ⭐ <b>Lua 와 자바가 같은 머리 문법을 읽는다</b> — 갈리면 평시에도 조건이 어긋난다.
     *
     * <p>이것을 CAS 로 잰다. 자바가 집은 토큰을 그대로 조건으로 넘기므로, Lua 가 <b>다른</b>
     * 수를 읽으면 그 자리에서 {@code -1} 이 되어 빈 {@code OptionalLong} 이 온다. 즉 "통과 =
     * 두 파서가 같은 수를 봤다" 이고, 둘 중 어느 쪽 값인지를 따로 단언할 필요가 없다.
     *
     * <p>갈렸던 벡터로 돌린다(실측 열 중 여섯 갈림). 갈리면 어떻게 되는지가 이 테스트가 막는
     * 것이다 — CAS 가 매 시도 {@code -1} 이라 {@code MAX_ATTEMPTS} 를 소진하고, 그때 던지는
     * 메시지가 "버전 키를 스크립트 밖에서 쓰는 경로" 를 가리킨다(범인은 스냅샷 본문이다).
     * 20자리 축은 더 나쁘다: Lua 의 {@code tonumber} 가 {@code 1e+20} 을 돌려주고 그것이
     * {@code SET} 으로 <b>두 키에 다시 써지면</b> 사람 손 없이 안 풀린다.
     */
    @Test
    @DisplayName("⭐ Lua 와 자바가 같은 머리 문법을 읽는다 — 갈리면 평시에도 CAS 가 깨진다")
    void luaAndJavaReadTheSameVersionGrammar() {
        List<String> vectors = List.of(
                "12abc\nbody",          // 숫자 뒤가 개행이 아니다
                " 12\nbody",            // 앞에 공백
                "-5\nbody",             // 부호
                "+7\nbody",             // 자바 parseLong 이 받는 부호
                "\t3\nbody",            // 앞에 탭
                "99999999999999999999\nbody", // 20자리 — Lua 가 1e+20 을 돌려주던 축
                "1\nbody",              // 정상
                "007\nbody",            // 앞이 0
                "1 \nbody",             // 숫자 뒤 공백
                "\nbody");              // 머리가 없다

        assertThat(vectors).hasSize(10);
        for (String vector : vectors) {
            redisTemplate.delete(List.of(SNAPSHOT, VERSION));
            redisTemplate.opsForValue().set(SNAPSHOT, vector);

            long token = publisher.currentVersion();
            assertThat(publisher.publishIfUnchanged(token, ordered("시발"), Set.of()))
                    .as("자바가 %s 를 토큰으로 집었는데 Lua 가 다른 수를 읽으면 CAS 가 깨진다: %s",
                            token, vector.replace("\n", "\\n"))
                    .isPresent();
        }
    }

    /**
     * ⭐ <b>버전 키가 다른 타입이어도 발행이 그것을 덮어 고친다.</b>
     *
     * <p>한때 자바가 이 경우에 던졌다 — 스크립트의 {@code TYPE} 가드는 그 키를 0 으로 두고
     * {@code SET} 이 타입 무관하게 덮으므로 <b>스크립트는 고칠 수 있었는데 그 스크립트가 애초에
     * 돌지 못했다.</b> {@code LPUSH} 한 줄로 모든 ADMIN 쓰기와 부팅 발행이 500 이고 복구가 수동
     * {@code DEL} 뿐이었다. 스냅샷 키 쪽은 같은 상황에서 0 으로 떨어뜨려 치유를 살려 뒀고,
     * 두 키가 같은 성질을 가져야 한다.
     */
    @Test
    @DisplayName("⭐ 타입이 오염된 버전 키도 발행이 덮어 고친다 — 두 키가 같은 성질을 갖는다")
    void aWrongTypedVersionKeyIsHealedByThePublish() {
        redisTemplate.opsForList().rightPush(VERSION, "1");

        long token = publisher.currentVersion();
        assertThat(token)
                .as("읽을 수 없는 키는 기준에 기여하지 않는다")
                .isZero();
        assertThat(publisher.publishIfUnchanged(token, ordered("시발"), Set.of()))
                .as("여기서 던지면 복구가 수동 DEL 뿐이다")
                .isPresent();
        assertThat(redisTemplate.opsForValue().get(VERSION)).isEqualTo("1");
    }

    /**
     * ⭐ <b>벡터 하나마다 세 명제를 잰다 — 일치 · 쓴 값이 읽히는가 · 그 칸이 보이는가.</b>
     *
     * <p>이 표가 자란 이력이 그대로 설계의 이력이다.
     * <ol>
     *   <li><b>일치</b>(처음) — 자바와 Lua 가 같은 판정인가. CAS 로 잰다.
     *   <li><b>닫힘</b>(다음) — 수락된 토큰의 후계자가 다시 수락되는가. <b>유한 공간에서는
     *       불가능하다</b>는 것이 여기서 드러났다 — 수락을 한 칸 좁히면 경계도 한 칸 내려간다.
     *   <li><b>쓴 값이 읽히는가 + 그 칸이 보이는가</b>(지금) — 2번을 문법 하나로 밀어붙였더니
     *       마지막 칸에서 <b>쓴 값이 자기 리더에게 거부됐다.</b> 발행은 200, 함대는 그 번호를
     *       읽지 못하고, 받는 쪽의 "아직 없다" 분기는 부팅에만 로그를 남기므로 <b>전 경로
     *       무음</b>이었다. 그래서 축을 둘로 쪼갰다 — 문법(읽힘)과 기준 자격(여기에 +1 을 쌓은
     *       결과도 읽힘). 경계 칸에서는 기준이 0 으로 떨어지고 v1 을 쓰는데, 그 v1 은 <b>읽히는
     *       번호</b>이고 그 재시작이 WARN 으로 남는다.
     * </ol>
     *
     * <p>2번을 분모에 넣은 것만으로는 부족했다 — 칸의 <b>존재</b>는 기록됐는데 그 칸에서
     * <b>무엇이 관측되는지</b>를 아무 단언도 재지 않았다. 그래서 이 표가 이제 그것도 잰다.
     */
    @Test
    @DisplayName("⭐ 문법 표 — 일치(CAS) · 쓴 값이 다시 읽히는가 · 경계 칸이 보이는가")
    void theGrammarIsConsistentAndEveryWrittenVersionIsReadable() {
        // ⚠️ 열이 셋이다. 한때 `base != 0` 과 `자격 있음` 을 하나로 단언했는데(`isEqualTo`)
        // 그 둘이 <값 0 인 키> 한 칸에서 갈린다 — 읽히고 자격도 통과하는데 base 가 0 이다.
        // 그 단언이 그 칸을 표에 넣지 못하게 막고 있었고, 바로 그 칸에서 경계 WARN 커버리지가
        // 사라졌다. 열을 쪼개야 그 칸이 분모에 든다.
        record Vector(String versionKey, boolean readable, boolean eligibleBase, long base) { }
        List<Vector> table = List.of(
                new Vector("0", true, true, 0L),              // ⭐ 읽힘·자격 ○ 인데 base 0
                new Vector("1", true, true, 1L),
                new Vector("007", true, true, 7L),
                new Vector("99999999999999", true, true, 99_999_999_999_999L),
                new Vector("999999999999997", true, true, 999_999_999_999_997L),
                new Vector("999999999999998", true, false, 0L),  // 읽히지만 +1 을 못 쌓는다
                new Vector("999999999999999", false, false, 0L), // 후계자가 16자리 — 문법 밖
                new Vector("1000000000000000", false, false, 0L),
                new Vector("9007199254740993", false, false, 0L),
                new Vector(" 12", false, false, 0L),
                new Vector("-5", false, false, 0L),
                new Vector("+7", false, false, 0L),
                new Vector("12abc", false, false, 0L),
                new Vector("", false, false, 0L));

        assertThat(table).hasSize(14);
        for (Vector v : table) {
            redisTemplate.delete(List.of(SNAPSHOT, VERSION));
            redisTemplate.opsForValue().set(VERSION, v.versionKey());

            long base = publisher.currentVersion();
            assertThat(base)
                    .as("기준 기대와 다르다: %s", v.versionKey())
                    .isEqualTo(v.base());

            // ⚠️ readable 열이 한 번도 안 읽히던 것을 고친다 — 선언된 분모가 장식이었다.
            OptionalLong token =
                    ProfanityDictionarySnapshotCodec.readVersionToken(v.versionKey());
            assertThat(token.isPresent())
                    .as("읽힘 기대와 다르다: %s", v.versionKey())
                    .isEqualTo(v.readable());
            assertThat(token.isPresent()
                            && ProfanityDictionarySnapshotCodec.isEligibleBase(token.getAsLong()))
                    .as("기준 자격 기대와 다르다 — base 와 <다른 축>이다: %s", v.versionKey())
                    .isEqualTo(v.eligibleBase());

            long published = publisher
                    .publishIfUnchanged(base, ordered("시발"), Set.of())
                    .orElseThrow(() -> new AssertionError(
                            "자바가 " + base + " 을 집었는데 Lua 가 다른 수를 읽었다: "
                                    + v.versionKey()));

            // ⭐ 쓴 값은 <언제나 읽혀야> 한다. 이것이 안 지켜졌을 때가 전 경로 무음이었다.
            assertThat(ProfanityDictionarySnapshotCodec.readVersionToken(
                            redisTemplate.opsForValue().get(VERSION)))
                    .as("쓴 값을 리더가 거부하면 함대가 그 버전을 읽지 못하고 그 상태가 무음이다: "
                            + "쓴 값=%s", published)
                    .hasValue(published);

            if (!v.eligibleBase()) {
                assertThat(published)
                        .as("기준이 0 으로 떨어진 칸은 v1 에서 다시 시작한다: %s", v.versionKey())
                        .isEqualTo(1L);
            }
        }
    }

    /**
     * ⭐ <b>반환 규약이 전역이다 — "{@code -1} 아닌 모든 값 = 발행된 버전" 이 아니다.</b>
     *
     * <p>⚠️ 이 가드에 테스트가 <b>0건</b>이었다. 근거로 적어 둔 것이 "리셋을 {@code -2} 로
     * 되돌린 변이에서 {@code OptionalLong[-2]} 를 성공으로 반환했다" 는 <b>일회성 관찰</b>이라,
     * 가드를 지우는 변경이 조용히 통과했다. 스크립트가 정적 상수라 반환값을 주입할 수 없어서
     * 판정을 메서드로 꺼냈고, 이 테스트가 그 자리를 전수로 잰다.
     */
    @ParameterizedTest(name = "반환={0}")
    @ValueSource(longs = {-2L, -3L, -100L, 0L})
    @DisplayName("⭐ 규약 밖의 반환을 성공으로 읽지 않는다 — 센티넬이 하나 늘면 조용히 틀린다")
    void aReturnOutsideTheContractIsNotReadAsSuccess(long returned) {
        assertThatThrownBy(() -> ProfanityDictionarySnapshotRedisPublisher.readReturn(returned))
                .as("성공으로 뭉개면 호출자가 '발행됐다' 로 로그를 남기고 함대에 안 깔린 것을 "
                        + "모른다: 반환=%s", returned)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("규약 밖의 값");
    }

    @Test
    @DisplayName("규약 안의 반환은 그대로 읽는다 — -1 은 조건 실패(빈 값), 1 이상은 버전")
    void aReturnInsideTheContractIsReadAsIs() {
        assertThat(ProfanityDictionarySnapshotRedisPublisher.readReturn(-1L)).isEmpty();
        assertThat(ProfanityDictionarySnapshotRedisPublisher.readReturn(1L)).hasValue(1L);
        assertThat(ProfanityDictionarySnapshotRedisPublisher.readReturn(42L)).hasValue(42L);
        assertThatThrownBy(() -> ProfanityDictionarySnapshotRedisPublisher.readReturn(null))
                .as("드라이버가 수를 안 주면 발행 여부를 확인할 수 없다 — 재시도로도 안 낫는다")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("재시도로 낫지 않는다");
    }

    @Test
    @DisplayName("⭐ 평시 첫 발행에는 경계 칸 WARN 이 안 나간다 — 거짓양성이면 그 WARN 이 죽는다")
    void aCleanFirstPublishProducesNoBoundaryWarning() {
        // 두 키가 다 없다 = 한 번도 발행되지 않았다. 기준이 0 이지만 그것은 정상이다.
        // ⚠️ 이 축에 단언이 0건이었다 — (snapshotPresent || versionPresent) 게이팅을 지우는
        // 변경이 조용히 통과하고, 그 변이는 <발행마다> WARN 을 뿌려 그 줄을 죽인다.
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(
                ProfanityDictionarySnapshotRedisPublisher.class);
        logger.addAppender(appender);
        try {
            assertThat(publisher.currentVersion()).isZero();
            assertThat(publisher.publishIfUnchanged(0L, ordered("시발"), Set.of())).hasValue(1L);

            assertThat(appender.list.stream()
                    .filter(event -> event.getLevel() == Level.WARN)
                    .toList())
                    .as("평시 경로에서 WARN 이 나가면 그 줄이 신호가 아니라 배경이 된다")
                    .isEmpty();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @DisplayName("⭐ 두 키가 다 있고 한쪽만 자격 없으면 다른 키로 떨어진다 — 1차 구현이 깨뜨린 성질")
    void oneIneligibleKeyFallsBackToTheOther() {
        // ⚠️ 이 칸이 테스트에 없었다 — 문법 표가 VERSION 한 키만 세팅한다. 1차 구현(거부)이
        // 깨뜨린 성질이 <부재> 쪽만 덮여 있었고, "둘 다 있는데 한쪽만" 이 분모 밖이었다.
        redisTemplate.opsForValue().set(SNAPSHOT, "7\n1\n0\n시발\n");
        redisTemplate.opsForValue().set(VERSION, "999999999999998");

        assertThat(publisher.currentVersion())
                .as("자격 없는 키는 기여하지 않고 자격 있는 머리가 기준이 된다")
                .isEqualTo(7L);
        assertThat(publisher.publishIfUnchanged(7L, ordered("시발"), Set.of()))
                .as("멈추지 않는다 — 1차 구현은 이 칸까지 거부해서 자기치유를 깼다")
                .hasValue(8L);
    }

    /**
     * ⭐ <b>기준이 0 인 <i>모든</i> 칸에서 WARN 이 나간다 — 술어가 덮는 집합을 잰다.</b>
     *
     * <p>⚠️ 이 테스트가 없을 때 그 술어가 조용히 좁아졌다. 사유 문장을 참으로 만들려고 게이트를
     * {@code head.isEmpty() && counter.isEmpty()} 로 바꿨는데, {@code base == 0} 과의 차이가
     * 정확히 <b>"읽히고 자격도 통과하는 값 0 을 든 키"</b> 한 칸이고 그 칸에서 WARN 이 사라졌다.
     * 역변이로 확인된 대로 <b>좁힘을 요구한 테스트가 0건</b>이었다 — 조건을 되돌려도 전량 초록.
     *
     * <p>그래서 "사유가 참인가" 가 아니라 <b>"같은 칸들을 아직 덮는가"</b> 를 잰다. 그 둘은
     * 다른 질문이고, 전자만 고치면 후자가 조용히 줄어든다.
     *
     * <p>⚠️ 값 0 인 키도 덮어야 하는 이유: 그 칸도 다음 발행이 v1 이고, 함대가 1 을 들고 있으면
     * 조용히 건너뛴다 — 경계 칸과 <b>같은 피해</b>다. "문법 밖이라서" 가 아니라 "기준이 0 이라서"
     * 가 그 WARN 의 조건이다.
     */
    @ParameterizedTest(name = "{0}=\"{1}\"")
    @MethodSource("zeroBaseCells")
    @DisplayName("⭐ 기준이 0 인 모든 칸에서 WARN 이 나간다 — 두 축 다")
    void everyZeroBaseCellWarns(String axis, String raw) {
        // ⚠️ 한때 이 벡터가 VERSION 만 세팅했다. @BeforeEach 가 SNAPSHOT 을 지우므로 여덟 칸
        // 전부 <머리가 부재>였고, 게이트를 `base == 0 && head.isEmpty()` 로 좁히는 역변이가
        // chat-core 전량(946건) 초록으로 통과했다 — C15 가 고친 바로 그 모양("좁힘을 요구한
        // 테스트가 0건")이 반대 축에 그대로 남아 있었다. 그래서 두 축을 다 돈다.
        redisTemplate.opsForValue().set(SNAPSHOT.equals(axis) ? SNAPSHOT : VERSION, raw);

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(
                ProfanityDictionarySnapshotRedisPublisher.class);
        logger.addAppender(appender);
        try {
            assertThat(publisher.currentVersion())
                    .as("이 벡터는 기준이 0 이어야 한다 — 아니면 이 테스트가 다른 것을 잰다: %s=%s",
                            axis, raw.replace("\n", "\\n"))
                    .isZero();

            assertThat(appender.list.stream()
                    .filter(event -> event.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList())
                    .as("기준이 0 이면 다음 발행이 v1 이고 함대가 1 을 들고 있으면 조용히 "
                            + "건너뛴다 — 그 칸이 무음이면 아무도 모른다: %s=%s",
                            axis, raw.replace("\n", "\\n"))
                    .anySatisfy(message -> assertThat(message)
                            .contains("기준이 0 으로 떨어졌다")
                            .as("어느 키가 왜 기준이 못 됐는지가 비면 '어느 키가 왜' 를 싣겠다는 "
                                    + "목적이 무너진다")
                            .doesNotContain("([])")
                            // 비었는지만 보면 부족하다 — 사유가 비었을 때 다른 문장으로
                            // 채우는 보충이 있으면 이 단정이 항진명제가 된다. <이 축의 이름>이
                            // 사유에 들었는지를 본다.
                            .as("이 축이 기준을 0 으로 붙잡고 있는데 사유가 그 축을 말하지 "
                                    + "않으면 당직자가 어느 키를 볼지 모른다: %s=%s",
                                    axis, raw.replace("\n", "\\n"))
                            .contains(SNAPSHOT.equals(axis) ? "스냅샷 본문" : "버전 키")
                            // 보충 문장이 되살아나면 이 단정이 깨진다 — 이 벡터의 키는
                            // 사라지지 않았다(세팅한 뒤 바로 읽는다).
                            .doesNotContain("사라졌다"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    /**
     * ⭐ <b>모르는 키는 두 축 중 하나의 결과절을 물려받지 않는다.</b>
     *
     * <p>두 결과절 헬퍼가 {@code key} 로 갈리는데 {@code else} 기본값을 두면
     * {@code tokenOf} 에 세 번째 키가 붙는 순간 그 키가 <b>조용히 한 축의 문장을
     * 물려받는다</b> — 그때 틀리는 것이 당직자 안내다.
     *
     * <p>오늘 키가 둘뿐이라 <b>이 벡터 없이는 그 분기의 분모가 0</b> 이다({@code else} 로
     * 되돌리는 변이가 전량 초록으로 살아남는다). 헬퍼를 패키지 공개로 둔 것이 이 벡터를
     * 가능하게 하고, 재는 것은 <b>미래 호출처가 상속할 계약</b>이다.
     */
    @Test
    @DisplayName("⭐ 모르는 키는 두 축 중 하나의 결과절을 물려받지 않는다")
    void anUnknownKeyInheritsNeitherAxisConsequence() {
        String thirdKey = "chat:dict:somewhere-else";

        assertThat(ProfanityDictionarySnapshotRedisPublisher.vanishedConsequence(thirdKey))
                .as("버전 축 문장을 물려받으면 '집행은 안 바뀐다' 를 모르는 키에 단정한다")
                .doesNotContain("실행 중 Pod 의 집행은 안 바뀐다")
                .doesNotContain("번들 사전으로 돈다")
                .contains("여기 적혀 있지 않다");
        assertThat(ProfanityDictionarySnapshotRedisPublisher.zeroConsequence(thirdKey))
                .doesNotContain("기준이 그쪽으로 수렴해")
                .doesNotContain("Pod 마다 이미 적용한 번호로 갈린다")
                .contains("여기 적혀 있지 않다");

        // 아는 두 키는 그대로여야 한다 — 아니면 이 단정이 가드가 아니라 전건 무력화를 잰다.
        assertThat(ProfanityDictionarySnapshotRedisPublisher.vanishedConsequence(SNAPSHOT))
                .contains("번들 사전으로 돈다");
        assertThat(ProfanityDictionarySnapshotRedisPublisher.vanishedConsequence(VERSION))
                .contains("실행 중 Pod 의 집행은 안 바뀐다");
        assertThat(ProfanityDictionarySnapshotRedisPublisher.zeroConsequence(SNAPSHOT))
                .contains("Pod 마다 이미 적용한 번호로 갈린다");
        assertThat(ProfanityDictionarySnapshotRedisPublisher.zeroConsequence(VERSION))
                .contains("기준이 그쪽으로 수렴해");
    }

    /**
     * 두 키 — 아래 두 테스트가 <b>본문에서</b> 이 목록을 돈다.
     *
     * <p>두 칸은 중복이 <b>아니다</b>. {@code currentVersion()} 이 스냅샷을 먼저 읽으므로
     * <b>버전 축이 사라지는 칸에서만</b> 사라짐 사유가 쌓일 때 {@code reasons} 가 이미 차 있고,
     * 그 칸만 {@code if (present && reasons.isEmpty())} 와
     * {@code if (present && "스냅샷 본문".equals(what))} 변이를 죽인다. 값 0 쪽도 결과절이
     * 키마다 달라 두 칸이 필요하다.
     *
     * <p><b>파라미터화 대신 본문 루프</b>를 쓰고 각 테스트가 <b>이 목록 자체를 단언</b>한다.
     * 파라미터 소스였을 때는 칸을 하나로 줄이는 변경이 아무것도 깨뜨리지 않고 통과했고
     * (그 상태에서 위 두 변이가 되살아난다), 그 집합을 리플렉션으로 검사하는 별 테스트는
     * {@code @Disabled} 하나에 조용히 비워졌다. 목록 단언을 본문에 두면 <b>줄이는 변경은
     * 단언을 깨고, 끄는 변경은 눈에 보이는 테스트 하나를 끄는 일</b>이 된다.
     */
    private static final List<String> BOTH_KEYS = List.of(SNAPSHOT, VERSION);

    /**
     * ⭐ <b>값 0 을 든 키는 기준이 0 이 <i>아닌</i> 칸에서도 말한다 — 게이트가 침묵하는 칸.</b>
     *
     * <p>이 축에 자기 {@code log.warn} 이 없으면 <b>"다른 키가 자격 있는 토큰을 들고 있으면
     * 기준이 그쪽이라 피해가 0"</b> 에 기대야 하는데, 그 근거가 <b>키마다 다르다</b>:
     *
     * <ul>
     *   <li><b>본문 머리 0 · 버전 키 5</b> — 기준이 5 라 게이트가 거짓이다(무음). 수신 쪽
     *       결과는 그 Pod 이 <b>이미 무엇을 적용했는지로 갈리고</b> 발행자는 그 축을 볼 수
     *       없다 — {@code aZeroHeadedBodySplitsTheFleetByPodState} 가 그쪽을 잰다.</li>
     *   <li><b>본문 머리 5 · 버전 키 0</b> — 기준이 5 로 수렴하고 발행·적용이 정상이다.
     *       여기서는 그 근거가 참이다.</li>
     * </ul>
     *
     * <p>즉 비대칭이 <b>축 단위가 아니라 키 단위</b>였다. 그 분류를 없애려고 다섯 사유 전부에
     * 자기 {@code log.warn} 을 뒀고, 이 벡터가 <b>게이트로는 안 잡히는 칸</b>을 못 박는다 —
     * 기준이 0 이 아니므로 위 {@code everyZeroBaseCellWarns} 가 이 칸을 아예 안 본다.
     */
    @Test
    @DisplayName("⭐ 값 0 을 든 키는 기준이 0 이 아닌 칸에서도 말한다")
    void aKeyHoldingZeroWarnsEvenWhenTheBaseIsNotZero() {
        assertThat(BOTH_KEYS)
                .as("한 칸으로 줄이면 반대 키의 결과절을 아무도 보지 않는다")
                .containsExactlyInAnyOrder(SNAPSHOT, VERSION);
        BOTH_KEYS.forEach(this::assertKeyHoldingZeroWarns);
    }

    private void assertKeyHoldingZeroWarns(String zeroKey) {
        redisTemplate.delete(BOTH_KEYS);
        String other = SNAPSHOT.equals(zeroKey) ? VERSION : SNAPSHOT;
        redisTemplate.opsForValue().set(zeroKey,
                SNAPSHOT.equals(zeroKey) ? "0\n1\n0\n시발\n" : "0");
        redisTemplate.opsForValue().set(other,
                SNAPSHOT.equals(other) ? "5\n1\n0\n시발\n" : "5");

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(
                ProfanityDictionarySnapshotRedisPublisher.class);
        logger.addAppender(appender);
        try {
            assertThat(publisher.currentVersion())
                    .as("다른 키가 기준을 주므로 기준은 0 이 아니다 — 그래서 게이트가 침묵한다")
                    .isEqualTo(5L);

            List<String> warns = appender.list.stream()
                    .filter(event -> event.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();

            assertThat(warns)
                    .as("기준이 0 이 아니라 게이트 WARN 이 안 나가므로, 이 축이 자기 줄을 "
                            + "안 가지면 이 칸이 전 경로 무음이다: 값 0 인 키=%s", zeroKey)
                    .anySatisfy(message -> assertThat(message)
                            .contains("값 0 을 들고 있다")
                            .contains(SNAPSHOT.equals(zeroKey) ? "스냅샷 본문" : "버전 키"));

            assertThat(warns)
                    .as("결과절은 키마다 다르다 — 본문 머리가 0 인 칸만 Pod 상태로 갈린다")
                    .anySatisfy(message -> assertThat(message)
                            .contains(SNAPSHOT.equals(zeroKey)
                                    ? "Pod 마다 이미 적용한 번호로 갈린다"
                                    : "기준이 그쪽으로 수렴해 발행·적용은 정상이다"));
            if (SNAPSHOT.equals(zeroKey)) {
                // 재시작을 포괄 금지하지도, 권하지도 않는다. 머리 0 은 발행이 쓰지 않는
                // 값이라 이 본문은 검수되지 않은 쓰기일 수 있고, 그 화이트리스트가 욕설을
                // 품으면 재시작이 검증된 번들로 되돌리는 유일한 즉시 수단이다. 반대로 맞는
                // 사전을 들고 있는 Pod 에는 재시작이 그것을 잃는 길이다. 그래서 조건을 싣는다.
                assertThat(warns)
                        .as("조건 없는 '재시작하지 말 것' 은 밴드 밖 본문이 집행 중인 Pod 에서 "
                                + "유일한 즉시 수단을 막는다")
                        .anySatisfy(message -> assertThat(message)
                                .contains("이 본문이 검수된 것인지 먼저 확인"));
            }
            // 게이트가 침묵하는지는 단언하지 않는다. 그것은 <구성의 귀결>(기준이 5 라서)이고,
            // 금지로 못 박으면 게이트를 !reasons.isEmpty() 로 넓히는(정보가 늘어나는) 변경이
            // 이 칸에서 깨진다. 이 벡터가 재는 것은 "게이트가 안 말해도 이 축이 자기 줄로
            // 말한다" 이고 위 두 단정이 그것을 센다.
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    /**
     * ⭐ <b>확인과 읽기 사이에 사라진 키는 <i>어느 축이든</i> 그 축의 이름으로 사유에 남는다.</b>
     *
     * <p>이 사유를 {@code currentVersion()} 이 {@code reasons.isEmpty()} 일 때만 보충하면,
     * 다른 키에 사유가 있는 칸에서 사라진 키의 사유가 WARN 에서 빠진다. 더 나쁜 것은 그 보충이
     * <b>다른 사유가 지워져도 WARN 을 비지 않게 만들어</b> 두 축 벡터의
     * {@code doesNotContain("([])")} 를 항진명제로 바꾸는 것이다.
     *
     * <p><b>그 보충을 지우는 것만으로는 이 벡터가 그 모양을 못 본다.</b> 모든 {@code GET} 을
     * {@code null} 로 고정한 한 칸에서는 {@code reasons} 가 <b>비어 있어서</b> 보충도 똑같이
     * 돌고, {@code if (present && reasons.isEmpty())} 와
     * {@code if (present && "스냅샷 본문".equals(what))} 변이가 전량 초록으로 살아남는다.
     *
     * <p>그래서 칸을 <b>두 축으로</b> 벌리고, 사라지지 <i>않는</i> 축에 문법 밖 값을 둬서
     * {@code reasons} 가 <b>이미 차 있는</b> 상태를 만든다. {@code currentVersion()} 이 스냅샷을
     * 먼저 읽으므로 두 변이를 죽이는 것은 <b>버전 축이 사라지는 칸</b>이다 — 그 칸에서만
     * {@code reasons.isEmpty()} 가 거짓이다.
     *
     * <p>이 칸은 실제 Redis 로 못 만든다. {@code hasKey} 두 번 + {@code GET} 두 번이 원자적이지
     * 않은 4 왕복이고, 그 사이에 축출·DEL 이 끼는 순간을 테스트가 잡을 수 없다. 그래서 <b>그
     * 틈만</b> 목으로 벌린다 — 이 클래스의 유일한 목이고 나머지 벡터는 전부 실제 Redis 다.
     */
    @Test
    @DisplayName("⭐ 확인 뒤 사라진 키는 어느 축이든 그 축의 이름으로 사유에 남는다")
    void aKeyThatVanishesBetweenCheckAndReadStillNamesItsAxis() {
        assertThat(BOTH_KEYS)
                .as("버전 축이 사라지는 칸만 reasons.isEmpty() 변이를 죽인다 — 줄이면 되살아난다")
                .containsExactlyInAnyOrder(SNAPSHOT, VERSION);
        BOTH_KEYS.forEach(this::assertVanishedKeyNamesItsAxis);
    }

    @SuppressWarnings("unchecked")
    private void assertVanishedKeyNamesItsAxis(String vanishing) {
        String surviving = SNAPSHOT.equals(vanishing) ? VERSION : SNAPSHOT;
        String vanishedLabel = SNAPSHOT.equals(vanishing) ? "스냅샷 본문" : "버전 키";
        String survivingLabel = SNAPSHOT.equals(vanishing) ? "버전 키" : "스냅샷 본문";

        StringRedisTemplate racing = Mockito.spy(redisTemplate);
        ValueOperations<String, String> racingOps = Mockito.mock(ValueOperations.class);
        Mockito.when(racingOps.get(vanishing)).thenReturn(null);
        // 살아남는 축은 문법 밖 값을 든다 — 그래야 사라지는 축이 처리될 때 reasons 가 차 있다.
        Mockito.when(racingOps.get(surviving)).thenReturn("12abc");
        Mockito.doReturn(racingOps).when(racing).opsForValue();
        Mockito.doReturn(true).when(racing).hasKey(SNAPSHOT);
        Mockito.doReturn(true).when(racing).hasKey(VERSION);
        ProfanityDictionarySnapshotRedisPublisher racingPublisher =
                new ProfanityDictionarySnapshotRedisPublisher(racing);

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(
                ProfanityDictionarySnapshotRedisPublisher.class);
        logger.addAppender(appender);
        try {
            assertThat(racingPublisher.currentVersion())
                    .as("두 키 다 기여하지 않으므로 기준은 0 이다")
                    .isZero();

            List<String> warns = appender.list.stream()
                    .filter(event -> event.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();

            assertThat(warns)
                    .as("게이트 WARN 이 두 축의 사유를 다 실어야 한다 — 사라지는 축이 나중에 "
                            + "처리되는 칸에서 바로 그것이 빠졌다")
                    .anySatisfy(message -> assertThat(message)
                            .contains("기준이 0 으로 떨어졌다")
                            .doesNotContain("([])")
                            .contains(vanishedLabel + "=확인 뒤 읽기 사이에 사라졌다")
                            .contains(survivingLabel + "=문법 밖이다")
                            // ⚠️ 여기서 금지하는 것은 <사라졌다 사유>뿐이다. present 가
                            // 게이팅하는 것은 raw == null 분기 하나이고, 확인에서 거짓이던 축에
                            // 키가 <생기면> raw != null 이라 정상 경로로 내려가 "문법 밖" ·
                            // "값이 0" 이 정당하게 붙는다. 그래서 금지를 "=확인 뒤" 까지로
                            // 좁힌다 — doesNotContain("버전 키") 는 그 올바른 동작까지 막는다.
                            .doesNotContain(survivingLabel + "=확인 뒤"));

            assertThat(warns)
                    .as("사라짐은 base 가 0 이 아닌 칸에서도 말해야 하므로 자기 WARN 을 갖는다")
                    .anySatisfy(message -> assertThat(message)
                            .contains("존재 확인 뒤 읽기 사이에 사라졌다")
                            .contains(vanishedLabel)
                            // 결과절까지 본다. 두 축에 같은 문장을 쓰면 한쪽에서 거짓이 되고
                            // (버전 키 유실만으로는 리더가 본문 머리로 떨어져 집행이 그대로다)
                            // 그 거짓이 당직자를 불필요한 전 Pod 재시작으로 보낸다. 라벨만
                            // 보는 단정은 그것을 안 잡는다.
                            .contains(SNAPSHOT.equals(vanishing)
                                    ? "본문을 못 찾아 번들 사전으로 돈다"
                                    : "실행 중 Pod 의 집행은 안 바뀐다")
                            // 조사가 라벨에 붙으면 "버전 키이" 가 된다 — 라벨을 자리= 로 뗐다.
                            .doesNotContain(vanishedLabel + "이 존재"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    /**
     * ⭐ <b>두 축이 섞인 칸에서 사유가 둘 다 뜬다.</b>
     *
     * <p>⚠️ 사유를 {@code reasons.isEmpty()} 일 때만 채우던 때는, 다른 키에 사유가 있으면
     * <b>base 를 0 으로 붙잡고 있는 또 한 축(값 0 키)이 WARN 에서 사라졌다</b> — 225칸 중
     * 36칸이 그 모양이다. 게이트 주석이 "사유는 {@code reasons} 가 따로 말한다" 로 분업을
     * 선언했으므로 그 칸에서 선언이 깨진다.
     *
     * <p>위의 두 축 벡터는 <b>한 키만</b> 세팅하므로 이 모양을 못 본다 — 그래서 별 칸이다.
     * "그 수정이 덮는 집합" 을 벡터로 못 박는 것이 이 아크의 남은 질문이고, 이것이 그 한 칸이다.
     */
    @Test
    @DisplayName("⭐ 머리는 값 0 · 버전은 문법 밖 — 사유가 둘 다 뜬다")
    void aMixedZeroBaseCellNamesBothReasons() {
        redisTemplate.opsForValue().set(SNAPSHOT, "0\n1\n0\n시발\n");
        redisTemplate.opsForValue().set(VERSION, "12abc");

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(
                ProfanityDictionarySnapshotRedisPublisher.class);
        logger.addAppender(appender);
        try {
            assertThat(publisher.currentVersion()).isZero();

            assertThat(appender.list.stream()
                    .filter(event -> event.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.contains("기준이 0 으로 떨어졌다"))
                    .toList())
                    .as("한 축만 뜨면 당직자가 나머지 축을 고치고도 기준이 안 올라가는 것을 본다")
                    .singleElement()
                    .satisfies(message -> assertThat(message)
                            .contains("스냅샷 본문=읽히고 자격도 통과하는데 값이 0 이다")
                            .contains("버전 키=문법 밖이다")
                            .as("키가 사라진 것이 아니다 — 그 사유가 뜨면 진단이 엉뚱해진다")
                            .doesNotContain("키가 사라졌다"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> zeroBaseCells() {
        // 기준을 0 으로 만드는 값들 — 값 0(읽힘·자격 ○) · 읽히지만 자격 없음 · 문법 밖 넷.
        List<String> raws = List.of("0", "0\n1\n0\n시발\n", "999999999999998",
                "999999999999999", "1000000000000000", "9007199254740993", " 12", "-5", "+7",
                "12abc");
        return raws.stream().flatMap(raw -> java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(VERSION, raw),
                org.junit.jupiter.params.provider.Arguments.of(SNAPSHOT, raw)));
    }

    /**
     * ⭐ <b>경계 칸이 조용하지 않다.</b>
     *
     * <p>이 칸의 결함은 번호 선택이 아니라 <b>전 경로 무음</b>이었다 — 발행 200 · 함대 미집행 ·
     * 로그 0줄. 쓰는 번호를 "읽히는 값" 으로 바꿨어도 남는 위험이 하나 있다(함대가 이미 1 을
     * 들고 있으면 그 v1 을 건너뛴다). 그 위험이 남는 이유는 <b>"버전만 비교한다" 는 선택</b>이고
     * (받는 쪽은 본문을 손에 들고 있다) 휘발 저장 때문이 아니다 — 그 축과 가격은
     * {@code modules/chat/AGENTS.md} 에 있다. 이 자리에서 할 수 있는 것은 <b>보이게</b> 만드는
     * 것이다.
     */
    @Test
    @DisplayName("⭐ 기준이 0 으로 떨어지면 WARN 이 남는다 — 이 칸이 무음이던 것이 결함이었다")
    void aBaseThatFallsToZeroIsNotSilent() {
        redisTemplate.opsForValue().set(VERSION, "999999999999998");

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(
                ProfanityDictionarySnapshotRedisPublisher.class);
        logger.addAppender(appender);
        try {
            assertThat(publisher.currentVersion()).isZero();

            assertThat(appender.list.stream()
                    .filter(event -> event.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList())
                    .as("키가 있는데 기준이 0 이면 다음 발행이 v1 에서 다시 시작한다 — 조용하면 "
                            + "함대가 그것을 건너뛰어도 아무도 모른다")
                    .anySatisfy(message -> assertThat(message)
                            // 사유 — "기여 없음" 과 "값 0" 을 뭉개면 정상 값 0 에도 거짓 사유가 붙는다.
                            .contains("기준이 0 으로 떨어졌다")
                            .contains("v1 에서 다시 시작")
                            .as("어느 키가 왜 기준이 못 됐는지가 없으면 당직자가 둘을 다 뒤진다")
                            .contains("읽히지만 +1 을 쌓으면")
                            // 확인 — 적용 성공은 받는 쪽 INFO 로 남으므로 그 부재가 판정이다.
                            // 한때 이 WARN 이 "받는 쪽에 흔적이 없다 · 기능 확인이 유일" 이라
                            // 적었는데, 같은 저장소의 FilterWordChange.version javadoc 이 이미
                            // 그 INFO 와 맞춰 보라고 지시하고 있었다.
                            .as("확인 방법이 틀리면 당직자가 없는 줄을 찾다가 '확인했다' 로 닫는다")
                            .contains("갈아 끼웠다")
                            // 복구 — 두 키 DEL 은 교착을 재생산한다(실측: 또 v1 → 또 건너뛴다).
                            // 실효 복구는 같은 쓰기를 한 번 더 보내는 것이다(기준 1 → v2).
                            .as("지시를 그대로 따르면 그 상태가 풀려야 한다 — DEL 은 v1 을 다시 쓴다")
                            .contains("한 번 더 보낸다")
                            .doesNotContain("두 키를 지워"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    /**
     * ⭐ <b>큰 버전이 키에 지수 표기로 써지지 않는다.</b>
     *
     * <p>Lua 가 두 키에 같은 수를 쓰는데 <b>포맷터가 둘</b>이었다. {@code SET KEYS[2], version} 은
     * Redis 의 숫자 인자 변환(정확)을 타고 {@code version .. '\n' .. body} 는 Lua 의 {@code ..}
     * 를 타는데 그것은 {@code %.14g} 다. 실측(Redis 7.4.12): 값이 15자리가 되는 순간
     * {@code 100000000000000} → {@code 1e+14} 다.
     *
     * <p>그 상태가 <b>영구</b>다. 매 발행이 같은 머리를 다시 쓰므로 자기치유가 없고(3회 반복
     * 실측에서 머리가 계속 {@code 1e+14}), {@code Codec.decode} 의 엄격 실패에 걸려 전 Pod 이
     * 이후 모든 발행을 거부하는데 ADMIN 쓰기는 전부 200 이다. 복구는 두 키 동시 삭제뿐이었다.
     *
     * <p>도달 경로는 유기적 증가(10<sup>14</sup>회 발행)가 아니라 <b>키에 큰 수가 손으로/덤프
     * 복원으로 들어오는 것</b>이고, 자리수 상한이 막으려던 적이 바로 그것이다 — 그런데 선언값
     * 18 과 실제 경계 13 사이가 다섯 자리 비어 있었다. {@code string.format('%d', …)} 로 포맷을
     * 모으고 상한을 산술 경계(15)로 맞췄다.
     */
    @Test
    @DisplayName("⭐ 15자리 버전도 10진수로 써진다 — 지수 표기가 키에 들어가면 영구 동결이다")
    void aFifteenDigitVersionIsWrittenInDecimal() {
        // 14자리 토큰 → 다음 버전이 15자리다. '..' 면 여기서 1e+14 가 된다.
        redisTemplate.opsForValue().set(VERSION, "99999999999999");

        long token = publisher.currentVersion();
        assertThat(token).isEqualTo(99_999_999_999_999L);
        assertThat(publisher.publishIfUnchanged(token, ordered("시발"), Set.of()))
                .hasValue(100_000_000_000_000L);

        assertThat(redisTemplate.opsForValue().get(VERSION)).isEqualTo("100000000000000");
        assertThat(redisTemplate.opsForValue().get(SNAPSHOT))
                .as("머리가 지수 표기면 양쪽 파서가 그것을 숫자로 못 읽고 자기치유가 없다")
                .startsWith("100000000000000\n");
        assertThat(publisher.currentVersion())
                .as("다음 조건 비교가 그 머리를 다시 읽는다 — 못 읽으면 토큰이 떨어진다")
                .isEqualTo(100_000_000_000_000L);
    }

    @Test
    @DisplayName("⭐ 16자리 머리는 문법 밖이다 — double 산술이 정확한 범위에서 끊는다")
    void aSixteenDigitHeadIsOutsideTheGrammar() {
        redisTemplate.opsForValue().set(VERSION, "1000000000000000");

        assertThat(publisher.currentVersion())
                .as("2^53 위에서는 tonumber 와 +1 이 정확하지 않다 — 문법에서 받지 않는다")
                .isZero();
        assertThat(publisher.publishIfUnchanged(0L, ordered("시발"), Set.of()))
                .as("못 읽은 키는 기여하지 않고 발행이 덮어 고친다")
                .hasValue(1L);
    }
}
