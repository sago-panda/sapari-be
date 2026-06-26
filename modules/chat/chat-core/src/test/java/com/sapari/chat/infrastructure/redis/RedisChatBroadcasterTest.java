package com.sapari.chat.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.ReactiveSubscription;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sapari.chat.application.protocol.ChatEnvelope;
import com.sapari.chat.application.protocol.ChatMessageTypeMixin;
import com.sapari.chat.domain.model.ChatMessage;
import com.sapari.chat.domain.model.ChatMessageType;
import com.sapari.chat.domain.model.ChatRole;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

class RedisChatBroadcasterTest {

    private ReactiveStringRedisTemplate redis;
    private RedisChatBroadcaster broadcaster;
    private ObjectMapper mapper;   // 검증용(동일 계약)
    private Sinks.Many<ReactiveSubscription.Message<String, String>> pattern;   // 상시 가동 hot 소스 주입

    private final UUID roomId = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private final String channel = "chat:pubsub:" + roomId;

    @BeforeEach
    void setUp() {
        redis = mock(ReactiveStringRedisTemplate.class);
        pattern = Sinks.many().multicast().onBackpressureBuffer();
        // 생성자가 패턴 구독을 즉시 연결(autoConnect 0)하므로 stub을 먼저 건다.
        doReturn(pattern.asFlux()).when(redis).listenToPattern("chat:pubsub:*");
        broadcaster = new RedisChatBroadcaster(redis);
        mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .addMixIn(ChatMessageType.class, ChatMessageTypeMixin.class);
    }

    @Test
    @DisplayName("publish — chat:pubsub:{roomId} 채널에 CHAT 봉투 JSON을 발행한다(round-trip 일치)")
    void publish_sends_chat_envelope_to_channel() throws Exception {
        when(redis.convertAndSend(anyString(), anyString())).thenReturn(Mono.just(1L));
        ChatMessage message = sampleMessage();

        StepVerifier.create(broadcaster.publish(roomId, message)).verifyComplete();

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(redis).convertAndSend(eq(channel), json.capture());
        ChatEnvelope sent = mapper.readValue(json.getValue(), ChatEnvelope.class);
        assertThat(sent).isInstanceOf(ChatEnvelope.ChatMsg.class);
        assertThat(((ChatEnvelope.ChatMsg) sent).message()).isEqualTo(message);
    }

    @Test
    @DisplayName("subscribe — 패턴 스트림의 해당 방 메시지를 ChatEnvelope로 역직렬화한다")
    void subscribe_deserializes_envelope() throws Exception {
        String wire = mapper.writeValueAsString(new ChatEnvelope.ChatMsg(sampleMessage()));

        StepVerifier.create(broadcaster.subscribe(roomId))
                .then(() -> pattern.tryEmitNext(message(channel, wire)))
                .expectNextMatches(e -> e instanceof ChatEnvelope.ChatMsg cm
                        && cm.message().equals(sampleMessage()))
                .thenCancel()
                .verify(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("subscribe — 다른 방 채널 메시지는 받지 않는다(roomId 필터)")
    void subscribe_filters_other_room() throws Exception {
        String otherWire = mapper.writeValueAsString(new ChatEnvelope.ChatMsg(sampleMessage()));
        String otherChannel = "chat:pubsub:" + UUID.randomUUID();

        StepVerifier.create(broadcaster.subscribe(roomId))
                .then(() -> pattern.tryEmitNext(message(otherChannel, otherWire)))
                .expectNoEvent(Duration.ofMillis(150))
                .thenCancel()
                .verify();
    }

    @Test
    @DisplayName("subscribe — 깨진 봉투 1건은 skip하고 스트림은 살아남는다(poison 생존)")
    void subscribe_skips_poison_message() throws Exception {
        String good = mapper.writeValueAsString(new ChatEnvelope.ChatMsg(sampleMessage()));

        StepVerifier.create(broadcaster.subscribe(roomId))
                .then(() -> {
                    pattern.tryEmitNext(message(channel, "{깨진 json"));
                    pattern.tryEmitNext(message(channel, good));
                })
                .expectNextMatches(e -> e instanceof ChatEnvelope.ChatMsg)   // 깨진 건 skip, 정상 1건만
                .thenCancel()
                .verify(Duration.ofSeconds(2));
    }

    @SuppressWarnings("unchecked")
    private ReactiveSubscription.Message<String, String> message(String channel, String body) {
        ReactiveSubscription.Message<String, String> m = mock(ReactiveSubscription.Message.class);
        when(m.getChannel()).thenReturn(channel);
        when(m.getMessage()).thenReturn(body);
        return m;
    }

    private ChatMessage sampleMessage() {
        return new ChatMessage(
                "65a1f2c3d4e5f60718293a4b",
                roomId,
                UUID.fromString("44444444-4444-4444-4444-444444444444"),
                "구매자닉",
                "buyer@example.com",
                ChatRole.BUYER,
                new ChatMessageType.Normal(),
                "안녕하세요",
                "안녕하세요",
                null,
                Instant.parse("2026-06-11T00:00:00Z"));
    }
}
