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
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.ReactiveSubscription;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;

import com.fasterxml.jackson.databind.JsonMappingException;
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

    @Test
    @DisplayName("subscribe — 역직렬화 실패 로그에 봉투 원문(PII)을 남기지 않는다")
    void subscribe_failure_log_does_not_leak_pii() {
        // given: 로그 캡처. senderId 자리에 이메일을 넣으면 Jackson이 실패한 값을 예외 메시지에 인용한다
        // (InvalidFormatException: Cannot deserialize value of type UUID from String "...").
        Logger logger = (Logger) LoggerFactory.getLogger(RedisChatBroadcaster.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        String pii = "victim@example.com";
        String poison = "{\"kind\":\"CHAT\",\"message\":{\"senderId\":\"" + pii + "\"}}";

        // when
        try {
            StepVerifier.create(broadcaster.subscribe(roomId))
                    .then(() -> pattern.tryEmitNext(message(channel, poison)))
                    .expectNoEvent(Duration.ofMillis(150))
                    .thenCancel()
                    .verify();
        } finally {
            logger.detachAppender(appender);
        }

        // then: 어디에도 원문이 남지 않는다
        assertThat(appender.list).isNotEmpty();
        assertThat(appender.list).allSatisfy(event -> assertThat(render(event)).doesNotContain(pii));
        // 그러면서 진단은 가능해야 한다 — 실패 종류와 걸린 필드 경로는 남는다(로그를 통째로 지우는 "수정" 방지)
        assertThat(render(appender.list.getFirst()))
                .contains("InvalidFormatException")
                .contains("senderId");
    }

    /** 로그 이벤트가 실제로 남기는 텍스트 전부 — 포맷된 메시지 + 예외 체인. */
    private String render(ILoggingEvent event) {
        StringBuilder text = new StringBuilder(event.getFormattedMessage());
        for (IThrowableProxy t = event.getThrowableProxy(); t != null; t = t.getCause()) {
            text.append(' ').append(t.getClassName()).append(' ').append(t.getMessage());
        }
        return text.toString();
    }

    @Test
    @DisplayName("실패 필드 경로 — 봉투에 없는 필드명이 와도 로그를 위조할 문자는 남지 않는다")
    void failed_field_path_strips_injection_from_unknown_property() throws Exception {
        // 나머지가 전부 유효해야 record가 생성되고, 그제서야 "모르는 필드"가 실패 사유가 된다.
        // (하나라도 어긋나면 생성 단계에서 먼저 터져 그 이름이 경로에 닿지 않는다.)
        String valid = mapper.writeValueAsString(new ChatEnvelope.ChatMsg(sampleMessage()));
        String injected = valid.replace("\"message\":{", "\"message\":{\"ev\\nil주입\":1,");

        Exception raw = catchDeserialize(injected);

        // 전제 검증 — 발행한 쪽이 지은 이름이 실제로 예외 경로에 담긴다. 그래서 걸러야 한다.
        assertThat(raw).isInstanceOf(JsonMappingException.class);
        assertThat(((JsonMappingException) raw).getPath())
                .anyMatch(ref -> ref.getFieldName() != null && ref.getFieldName().contains("\n"));

        assertThat(broadcaster.failedFieldPath(raw))
                .doesNotContain("\n")
                .doesNotContain("\r");
    }

    @Test
    @DisplayName("실패 필드 경로 — 정상 필드명은 그대로 남아 진단이 가능하다")
    void failed_field_path_keeps_real_field_names() {
        // senderId 자리에 UUID가 아닌 값 → 그 필드에서 매핑 실패
        Exception raw = catchDeserialize(
                "{\"kind\":\"CHAT\",\"message\":{\"senderId\":\"uuid아님\"}}");

        assertThat(broadcaster.failedFieldPath(raw)).isEqualTo("message.senderId");
    }

    /** 봉투 역직렬화를 실제로 시도해 터진 예외를 돌려준다(계약 그대로 — mixin/모듈 동일). */
    private Exception catchDeserialize(String json) {
        try {
            mapper.readValue(json, ChatEnvelope.class);
            throw new AssertionError("역직렬화가 실패해야 하는 입력인데 성공했다");
        } catch (Exception e) {
            return e;
        }
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
