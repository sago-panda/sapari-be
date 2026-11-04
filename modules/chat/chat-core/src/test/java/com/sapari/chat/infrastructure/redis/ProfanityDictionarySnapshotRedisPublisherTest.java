package com.sapari.chat.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashSet;
import java.util.List;
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
 * <p>목으로는 이것을 재지 못한다. Lua 안에서 {@code PUBLISH} 가 실제로 나가는지, {@code INCR} 이
 * 단조인지는 Redis 의 동작이고 스크립트 문자열을 단언하는 것으로는 같은 답이 아니다 —
 * 문자열이 맞아도 그 명령이 그 서버에서 거부될 수 있다(실측으로 Redis 7.4.12 에서 허용된다).
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
            long version = publisher.publish(ordered("시발", "개새끼"), ordered("시발점"));

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
        assertThat(publisher.publish(ordered("시발"), Set.of())).isEqualTo(1);
        assertThat(publisher.publish(ordered("시발", "지랄"), Set.of())).isEqualTo(2);
        assertThat(publisher.publish(ordered("시발"), Set.of())).isEqualTo(3);

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

        publisher.publish(ordered("시발"), Set.of());

        assertThat(ProfanityDictionarySnapshotCodec.decode(
                redisTemplate.opsForValue().get(SNAPSHOT)).words()).containsExactly("시발");
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

        publisher.publish(words, whitelist);

        // SADD 와 달리 SET 은 인자 하나라 Lua 스택 한계(unpack ~8,000)에 닿지 않는다 —
        // 강퇴 재적재가 청크를 써야 했던 이유가 여기엔 없다.
        ProfanityDictionarySnapshot decoded = ProfanityDictionarySnapshotCodec.decode(
                redisTemplate.opsForValue().get(SNAPSHOT));
        assertThat(decoded.words()).hasSize(1078).containsExactlyElementsOf(words);
        assertThat(decoded.whitelist()).hasSize(38).containsExactlyElementsOf(whitelist);
    }
}
