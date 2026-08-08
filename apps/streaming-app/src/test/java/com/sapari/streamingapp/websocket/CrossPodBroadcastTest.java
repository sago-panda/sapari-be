package com.sapari.streamingapp.websocket;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.sapari.chat.application.handler.ChatBroadcastSubscriber;
import com.sapari.chat.application.protocol.OutboundMessage;
import com.sapari.chat.domain.model.ChatMessage;
import com.sapari.chat.domain.model.ChatMessageType;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.model.ChatSession;
import com.sapari.chat.infrastructure.redis.ChatSessionRedisRepository;
import com.sapari.chat.infrastructure.redis.RedisChatBroadcaster;

import reactor.core.Disposable;
import reactor.test.StepVerifier;

/**
 * T10 G2 완료기준 — 한 Pod에서 발행한 메시지가 <b>다른 Pod</b>의 로컬 세션에 도달하는지(cross-pod fan-out).
 *
 * <p>HTTP 서버 2개 대신 Pod 경계를 컴포넌트로 시뮬한다: 각 Pod = 독립 RedisChatBroadcaster·ChatSessionRegistry·
 * ChatBroadcastSubscriber (같은 Redis 공유). Pod A가 publish → Redis pub/sub → Pod B 구독자 수신 → Pod B 로컬
 * 세션에 fan-out 됨을 확인. (단일 Pod 통과로 위장되는 silent failure 차단)
 */
@Testcontainers
class CrossPodBroadcastTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7").withExposedPorts(6379);

    private static LettuceConnectionFactory factoryA;
    private static LettuceConnectionFactory factoryB;

    // Pod A = 발행자
    private static RedisChatBroadcaster broadcasterA;
    // Pod B = 수신 Pod (구독자 + 로컬 세션)
    private static ChatSessionRegistry registryB;
    private static ChatBroadcastSubscriber subscriberB;

    private final UUID roomId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @BeforeAll
    static void setUp() throws Exception {
        factoryA = new LettuceConnectionFactory(redis.getHost(), redis.getFirstMappedPort());
        factoryA.afterPropertiesSet();
        factoryB = new LettuceConnectionFactory(redis.getHost(), redis.getFirstMappedPort());
        factoryB.afterPropertiesSet();

        ReactiveStringRedisTemplate templateA = new ReactiveStringRedisTemplate(factoryA);
        ReactiveStringRedisTemplate templateB = new ReactiveStringRedisTemplate(factoryB);

        broadcasterA = new RedisChatBroadcaster(templateA);

        registryB = new ChatSessionRegistry(new ChatSessionRedisRepository(templateB));
        subscriberB = new ChatBroadcastSubscriber(new RedisChatBroadcaster(templateB), registryB);
    }

    @AfterAll
    static void tearDown() {
        factoryA.destroy();
        factoryB.destroy();
    }

    @Test
    @DisplayName("G2 — Pod A 발행 → Pod B 로컬 세션이 수신 (cross-pod fan-out)")
    void publish_on_podA_reaches_session_on_podB() throws Exception {
        // given
        // Pod B: 방 구독 시작 + 로컬 세션 등록(비-owner → 마스킹 뷰)
        Disposable sub = subscriberB.subscribeRoom(roomId);
        ChatSession sessionB = new ChatSession(roomId, UUID.randomUUID(), ChatRole.BUYER, "구매자B", "b@example.com", false);
        registryB.register("sessB", sessionB).block();

        // Redis 패턴 구독이 실제로 활성화될 시간을 준다(pub/sub은 버퍼 없음 — 활성 전 발행분은 유실)
        Thread.sleep(1500);

        ChatMessage fromA = new ChatMessage(
                "65a1f2c3d4e5f60718290000", roomId, UUID.randomUUID(), "발신자A", "a@example.com",
                ChatRole.BUYER, new ChatMessageType.Normal(),
                "크로스팟 안녕", "크로스팟 안녕", null, Instant.parse("2026-06-27T00:00:00Z"));

        // when & then
        StepVerifier.create(registryB.outbound("sessB").next().timeout(Duration.ofSeconds(5)))
                .then(() -> broadcasterA.publish(roomId, fromA).block())
                .assertNext(out -> {
                    org.assertj.core.api.Assertions.assertThat(out.type()).isEqualTo("NORMAL");
                    org.assertj.core.api.Assertions.assertThat(out.displayMessage()).isEqualTo("크로스팟 안녕");
                    org.assertj.core.api.Assertions.assertThat(out.senderEmail()).isNull();   // 비-owner라 PII 제외
                })
                .verifyComplete();

        sub.dispose();
    }
}
