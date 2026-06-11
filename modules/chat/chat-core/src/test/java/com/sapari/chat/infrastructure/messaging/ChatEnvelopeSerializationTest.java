package com.sapari.chat.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import com.sapari.chat.domain.model.ChatMessage;
import com.sapari.chat.domain.model.ChatMessageType;
import com.sapari.chat.domain.model.ChatRole;

/**
 * ChatEnvelope 직렬화 계약 — api-app(손수 JSON 발행)과 streaming-app(역직렬화)이 타입을 공유하지 않으므로
 * 이 계약이 깨지면 KICK_EVENT가 전 Pod에서 조용히 유실된다. 계약을 테스트로 고정한다.
 */
class ChatEnvelopeSerializationTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Test
    @DisplayName("KickEvent 직렬화 — kind=KICK_EVENT + userId 두 필드만 나온다")
    void kickEvent_serializes_to_kind_and_userId_only() {
        UUID userId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        JsonNode json = objectMapper.valueToTree(new ChatEnvelope.KickEvent(userId));

        assertThat(json.get("kind").asText()).isEqualTo("KICK_EVENT");
        assertThat(json.get("userId").asText()).isEqualTo(userId.toString());
        assertThat(json.size()).isEqualTo(2); // kind + userId 외 다른 필드 없음
    }

    @Test
    @DisplayName("KickEvent 역직렬화 — api-app이 손수 만든 JSON 문자열을 ChatEnvelope로 복원한다(round-trip)")
    void kickEvent_deserializes_from_hand_written_json() throws Exception {
        UUID userId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        String handWritten = "{\"kind\":\"KICK_EVENT\",\"userId\":\"" + userId + "\"}";

        ChatEnvelope envelope = objectMapper.readValue(handWritten, ChatEnvelope.class);

        assertThat(envelope).isInstanceOf(ChatEnvelope.KickEvent.class);
        assertThat(((ChatEnvelope.KickEvent) envelope).userId()).isEqualTo(userId);
    }

    @Test
    @DisplayName("ChatMsg 직렬화 — kind=CHAT 으로 message 객체를 감싼다")
    void chatMsg_serializes_with_kind_CHAT_and_message() {
        ChatMessage message = sampleMessage();

        JsonNode json = objectMapper.valueToTree(new ChatEnvelope.ChatMsg(message));

        assertThat(json.get("kind").asText()).isEqualTo("CHAT");
        assertThat(json.get("message")).isNotNull();
        assertThat(json.get("message").get("roomId").asText()).isEqualTo(message.roomId().toString());
        assertThat(json.get("message").get("displayMessage").asText()).isEqualTo("안녕하세요");
    }

    private ChatMessage sampleMessage() {
        return new ChatMessage(
                "65a1f2c3d4e5f60718293a4b",
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
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
