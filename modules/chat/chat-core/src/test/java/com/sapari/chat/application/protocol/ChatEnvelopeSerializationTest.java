package com.sapari.chat.application.protocol;

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
 * ChatEnvelope 직렬화 계약 — live-app(손수 JSON 발행)과 streaming-app(역직렬화)이 타입을 공유하지 않으므로
 * 이 계약이 깨지면 KICK_EVENT가 전 Pod에서 조용히 유실된다. 계약을 테스트로 고정한다.
 */
class ChatEnvelopeSerializationTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        // addMixIn은 ChatEnvelope(ChatMsg)를 다루는 모든 ObjectMapper의 등록 의무 (ChatMessageTypeMixin javadoc)
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .addMixIn(ChatMessageType.class, ChatMessageTypeMixin.class);
    }

    @Test
    @DisplayName("KickEvent 직렬화 — kind=KICK_EVENT + userId 두 필드만 나온다")
    void kickEvent_serializes_to_kind_and_userId_only() {
        // given
        UUID userId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        JsonNode json = objectMapper.valueToTree(new ChatEnvelope.KickEvent(userId));

        // when & then
        assertThat(json.get("kind").asText()).isEqualTo("KICK_EVENT");
        assertThat(json.get("userId").asText()).isEqualTo(userId.toString());
        assertThat(json.size()).isEqualTo(2); // kind + userId 외 다른 필드 없음
    }

    @Test
    @DisplayName("KickEvent 역직렬화 — live-app이 손수 만든 JSON 문자열을 ChatEnvelope로 복원한다(round-trip)")
    void kickEvent_deserializes_from_hand_written_json() throws Exception {
        // given
        UUID userId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        String handWritten = "{\"kind\":\"KICK_EVENT\",\"userId\":\"" + userId + "\"}";

        ChatEnvelope envelope = objectMapper.readValue(handWritten, ChatEnvelope.class);

        // when & then
        assertThat(envelope).isInstanceOf(ChatEnvelope.KickEvent.class);
        assertThat(((ChatEnvelope.KickEvent) envelope).userId()).isEqualTo(userId);
    }

    @Test
    @DisplayName("ChatMsg 직렬화 — kind=CHAT 으로 message 객체를 감싼다")
    void chatMsg_serializes_with_kind_CHAT_and_message() {
        // given
        ChatMessage message = sampleMessage();

        JsonNode json = objectMapper.valueToTree(new ChatEnvelope.ChatMsg(message));

        // when & then
        assertThat(json.get("kind").asText()).isEqualTo("CHAT");
        assertThat(json.get("message")).isNotNull();
        assertThat(json.get("message").get("roomId").asText()).isEqualTo(message.roomId().toString());
        assertThat(json.get("message").get("displayMessage").asText()).isEqualTo("안녕하세요");
    }

    @Test
    @DisplayName("ChatMsg round-trip — 발행한 봉투를 다른 Pod가 ChatMessage(sealed type 포함)로 복원한다")
    void chatMsg_round_trips_through_wire_json() throws Exception {
        // given
        ChatMessage message = sampleMessage();

        String wire = objectMapper.writeValueAsString(new ChatEnvelope.ChatMsg(message));
        ChatEnvelope envelope = objectMapper.readValue(wire, ChatEnvelope.class);

        // when & then
        assertThat(envelope).isInstanceOf(ChatEnvelope.ChatMsg.class);
        assertThat(((ChatEnvelope.ChatMsg) envelope).message()).isEqualTo(message); // record 동등성 — type 복원 포함
    }

    @Test
    @DisplayName("System도 round-trip 가능 — 와이어 미탑재(로컬 렌더 전용)지만 매핑은 살아있는 전수성 방어다")
    void systemType_round_trips_despite_not_being_on_wire() throws Exception {
        // given
        String wire = objectMapper.writeValueAsString(new ChatMessageType.System("KICKED"));

        ChatMessageType back = objectMapper.readValue(wire, ChatMessageType.class);

        // when & then
        assertThat(back).isEqualTo(new ChatMessageType.System("KICKED")); // code 포함 복원 — 죽은 분기 아님
    }

    @Test
    @DisplayName("ChatMessageType 와이어 식별자 고정 — Normal=NORMAL, Notice=NOTICE (pubsub 계약)")
    void chatMessageType_wire_ids_are_pinned() {
        // given
        JsonNode normal = objectMapper.valueToTree(
                new ChatEnvelope.ChatMsg(sampleMessage())).get("message").get("type");

        // when & then
        assertThat(normal.get("kind").asText()).isEqualTo("NORMAL");

        ChatMessage notice = new ChatMessage(
                "65a1f2c3d4e5f60718293a4c",
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                UUID.fromString("55555555-5555-5555-5555-555555555555"),
                "판매자닉", "seller@example.com",
                ChatRole.SELLER, new ChatMessageType.Notice(),
                "공지입니다", "공지입니다", null,
                Instant.parse("2026-06-11T00:00:00Z"));
        JsonNode noticeType = objectMapper.valueToTree(
                new ChatEnvelope.ChatMsg(notice)).get("message").get("type");
        assertThat(noticeType.get("kind").asText()).isEqualTo("NOTICE");
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
