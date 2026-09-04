package com.sapari.chat.application.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sapari.chat.domain.model.ChatMessage;
import com.sapari.chat.domain.model.ChatMessageType;
import com.sapari.chat.domain.model.ChatRole;

/**
 * 와이어에 <b>빈 필드가 실리지 않는지</b> 고정한다.
 *
 * <p>{@code OutboundMessage}는 여덟 종류를 한 모양으로 나르는 합집합이라 어떤 종류든 절반 넘게 비어
 * 있다. 그 빈 자리를 그대로 내보내면 패딩이 본문보다 커지고, 방 하나가 초당 수백 건을 뿌리는 구조에서
 * 그 차이가 그대로 대역폭이 된다. 애너테이션 한 줄로 사라지는 비용이라 회귀도 한 줄로 일어난다 —
 * 그래서 테스트로 못 박는다.
 *
 * <p>매퍼는 실제 와이어와 같은 설정으로 만든다({@code ChatWebSocketHandler}가 시간을 ISO-8601
 * 문자열로 내보낸다). 설정이 다르면 여기서 통과해도 실제로 나가는 바이트는 다를 수 있다.
 */
@DisplayName("OutboundMessage — 값 없는 필드는 와이어에 싣지 않는다")
class OutboundMessageSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /**
     * 가장 흔한 프레임 — 남이 보낸 NORMAL 메시지를 받는 경우.
     *
     * <p><b>생산 경로를 그대로 태운다.</b> 손으로 지으면 팩토리가 바뀌어도 이 테스트가 초록이라,
     * 와이어 계약을 못 박는다면서 실제로 나가는 프레임을 안 보게 된다.
     */
    private OutboundMessage normalReceivedByOther() {
        ChatMessage message = ChatMessage.builder()
                .id("68b7f0c2e1a4b93d5c0a1234")
                .roomId(UUID.randomUUID())
                .senderId(UUID.randomUUID())
                .senderNickname("구매자닉")
                .senderEmail("buyer@example.com")
                .senderRole(ChatRole.BUYER)
                .type(new ChatMessageType.Normal())
                .originalMessage("안녕하세요")
                .displayMessage("안녕하세요")
                .clientMsgId("c1")
                .createdAt(Instant.parse("2026-09-04T00:00:00Z"))
                .build();
        // 방주인도 발신자도 아닌 세션 — 이메일·원문·clientMsgId가 모두 빠지는 조합이다
        return OutboundMessage.chat(message, false, false);
    }

    @Test
    @DisplayName("NORMAL 수신분에 null 키가 하나도 없다 — 15필드 중 실제로 채워지는 건 7개다")
    void normalFrameCarriesNoNullKeys() throws Exception {
        // when
        var json = mapper.readTree(mapper.writeValueAsString(normalReceivedByOther()));

        // then: 비어 있는 여덟 자리가 키째로 빠진다
        assertThat(json.size()).isEqualTo(7);
        for (var name : new String[] {"code", "senderEmail", "originalMessage",
                "userId", "activeCount", "retryAfterSeconds", "clientMsgId", "isRoomOwner"}) {
            assertThat(json.has(name)).as("빈 필드가 와이어에 남아 있다: %s", name).isFalse();
        }
    }

    @Test
    @DisplayName("값이 있는 필드는 그대로 실린다 — 빼는 것은 빈 자리뿐이다")
    void populatedFieldsSurvive() throws Exception {
        // when
        var json = mapper.readTree(mapper.writeValueAsString(normalReceivedByOther()));

        // then
        assertThat(json.get("type").asText()).isEqualTo("NORMAL");
        assertThat(json.get("displayMessage").asText()).isEqualTo("안녕하세요");
        assertThat(json.get("senderRole").asText()).isEqualTo("BUYER");
        // 시각은 epoch 숫자가 아니라 ISO-8601 문자열이어야 한다(프론트 계약)
        assertThat(json.get("createdAt").asText()).isEqualTo("2026-09-04T00:00:00Z");
    }

    @Test
    @DisplayName("빠진 자리를 되살리면 눈에 띄게 길어진다 — 그 차이가 곧 대역폭이다")
    void omittingBlanksIsMeasurablySmaller() throws Exception {
        // given: 실제로 나가는 프레임
        String slim = mapper.writeValueAsString(normalReceivedByOther());

        // when: 빠진 여덟 자리를 null로 되살려 본다(애너테이션이 없던 시절의 모양)
        ObjectNode padded = (ObjectNode) mapper.readTree(slim);
        for (String name : new String[] {"code", "senderEmail", "originalMessage",
                "userId", "activeCount", "retryAfterSeconds", "clientMsgId", "isRoomOwner"}) {
            padded.putNull(name);
        }

        // then: 본문("안녕하세요")보다 패딩이 크다. 애너테이션이 빠지면 이 길이로 되돌아간다
        assertThat(mapper.writeValueAsString(padded).length() - slim.length()).isGreaterThan(100);
    }

    @Test
    @DisplayName("SYSTEM은 code만 남는다 — 표시 문구는 클라가 code로 렌더한다")
    void systemFrameIsTiny() throws Exception {
        // given
        OutboundMessage system = OutboundMessage.system(SystemMessageCode.KICKED);

        // when
        var json = mapper.readTree(mapper.writeValueAsString(system));

        // then
        assertThat(json.size()).isEqualTo(4);   // type · code · senderId · senderNickname
        assertThat(json.get("code").asText()).isEqualTo("KICKED");
        assertThat(json.has("displayMessage")).isFalse();
    }
}
