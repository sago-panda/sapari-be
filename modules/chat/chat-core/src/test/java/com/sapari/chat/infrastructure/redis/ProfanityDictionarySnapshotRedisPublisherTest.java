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
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.sapari.chat.application.port.ProfanityDictionarySnapshot;

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
     * ⭐ <b>벡터 하나마다 두 명제를 같이 잰다 — 일치와 닫힘.</b>
     *
     * <p>이 표가 생긴 이유: <b>일치만 재면 영원히 안 걸리는 결함이 있다.</b> 읽는 넷(Lua
     * {@code token()} · {@code readVersionToken} · {@code decode} · 리더)은 이미 일치했고 쓰는
     * 포맷도 하나였는데, 결함은 그 사이가 아니라 <b>문법이 자기가 만드는 값에 대해 닫혀 있지
     * 않다</b>는 데서 나왔다. 그래서 명제를 둘로 적는다:
     * <ol>
     *   <li><b>일치</b> — 그 벡터를 자바와 Lua 가 같은 판정으로 보는가. CAS 로 잰다(자바가 집은
     *       토큰을 그대로 조건으로 넘기므로 Lua 가 다른 수를 읽으면 그 자리에서 {@code -1} 이다).
     *   <li><b>닫힘</b> — 그 벡터가 <b>수락되면</b>, 발행이 만드는 후계자({@code token + 1})도
     *       같은 문법 안인가. 아니면 쓰는 순간 두 키가 못 읽히는 값이 되고 토큰이 0 으로 떨어져
     *       낡은 발행자가 이긴다(ABA 재개방).
     * </ol>
     *
     * <p>2번이 {@code 999999999999999} 하나에서 깨졌고, 그 값은 이 클래스가 이미 <b>거부 벡터로
     * 들고 있던</b> {@code "1000000000000000"} 의 전신이다 — 16자리가 거부되는 것은 못 박았는데
     * 발행이 바로 그 값을 만든다는 것은 안 봤다. 두 명제가 한 표에 있으면 그 두 줄이 서로를
     * 가리킨다.
     */
    @Test
    @DisplayName("⭐ 문법 표 — 벡터마다 일치(CAS)와 닫힘(후계자)을 같이 잰다")
    void theGrammarIsConsistentAndClosedUnderItsOwnSuccessor() {
        record Vector(String versionKey, boolean accepted, boolean closed) { }
        List<Vector> table = List.of(
                new Vector("1", true, true),
                new Vector("007", true, true),
                new Vector("99999999999999", true, true),       // 14자리 → 후계자 15자리
                new Vector("999999999999999", true, false),      // 15자리 → 후계자 16자리, 안 닫힌다
                new Vector("1000000000000000", false, true),      // 16자리 — 문법 밖
                new Vector("9007199254740993", false, true),      // 2^53+1 — double 이 부정확
                new Vector(" 12", false, true),
                new Vector("-5", false, true),
                new Vector("+7", false, true),
                new Vector("12abc", false, true));

        assertThat(table).hasSize(10);
        for (Vector v : table) {
            redisTemplate.delete(List.of(SNAPSHOT, VERSION));
            redisTemplate.opsForValue().set(VERSION, v.versionKey());

            long token = publisher.currentVersion();
            assertThat(token != 0L)
                    .as("수락 기대와 다르다 — 수락되면 토큰이 0 이 아니어야 한다: %s", v.versionKey())
                    .isEqualTo(v.accepted());

            if (!v.closed()) {
                assertThatThrownBy(
                        () -> publisher.publishIfUnchanged(token, ordered("시발"), Set.of()))
                        .as("후계자가 문법 밖인데 썼다 — 두 키가 못 읽히는 값이 되고 낡은 발행자가 "
                                + "expected=0 으로 이긴다: %s", v.versionKey())
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("번호 공간이 고갈");
                assertThat(redisTemplate.opsForValue().get(VERSION))
                        .as("거부했으면 아무것도 쓰지 않아야 한다")
                        .isEqualTo(v.versionKey());
                continue;
            }
            assertThat(publisher.publishIfUnchanged(token, ordered("시발"), Set.of()))
                    .as("자바가 %s 를 토큰으로 집었는데 Lua 가 다른 수를 읽으면 CAS 가 깨진다: %s",
                            token, v.versionKey())
                    .isPresent();
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
