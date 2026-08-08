package com.sapari.chat.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

/**
 * 이 커맨드의 앞쪽 필드(roomId·sender*·isRoomOwner·isRoomAlive)는 <b>서버가 세션에서 채우는 값</b>이고,
 * 뒤쪽(messageType·content·clientMsgId)만 클라이언트에서 온다. 컴팩트 생성자는 그 경계가 지켜졌는지를
 * 마지막으로 확인하는 자리다 — 핸들러가 실수로 클라 입력을 신뢰 필드에 넣으면 여기서 걸려야 한다.
 *
 * <p>무엇을 검증하지 <b>않는지</b>도 함께 고정한다. 본문 길이·닉네임·이메일은 메시지 종류에 따라 유무가
 * 달라서 전송 흐름의 입력검증이 책임진다. 여기서 같이 막으면 그 결정이 두 곳으로 갈라진다.
 */
@DisplayName("SendChatCommand 불변식 — 신뢰 필드는 비어 있을 수 없다")
class SendChatCommandTest {

    private final UUID roomId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID senderId = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private SendChatCommand command(UUID room, UUID sender, String role, String type) {
        return new SendChatCommand(room, sender, role, false, true,
                "구매자", "b@example.com", type, "안녕", "c1");
    }

    @Test
    @DisplayName("정상 입력 → 생성되고 값이 그대로 실린다")
    void validCommand() {
        // when
        SendChatCommand c = command(roomId, senderId, "BUYER", "NORMAL");

        // then
        assertThat(c.roomId()).isEqualTo(roomId);
        assertThat(c.senderId()).isEqualTo(senderId);
        assertThat(c.senderRole()).isEqualTo("BUYER");
        assertThat(c.messageType()).isEqualTo("NORMAL");
        assertThat(c.isRoomAlive()).isTrue();
    }

    @Test
    @DisplayName("roomId 없음 → 거부(어느 방에 저장·중계할지 정해지지 않는다)")
    void roomId_isRequired() {
        // when & then
        assertThatThrownBy(() -> command(null, senderId, "BUYER", "NORMAL"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("roomId");
    }

    @Test
    @DisplayName("senderId 없음 → 거부(발신자 없는 메시지는 강퇴·차단의 대상을 잃는다)")
    void senderId_isRequired() {
        // when & then
        assertThatThrownBy(() -> command(roomId, null, "BUYER", "NORMAL"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("senderId");
    }

    @ParameterizedTest(name = "senderRole=[{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("senderRole이 비면 거부 — 공백도 포함(권한 판정의 축이라 blank를 통과시키면 안 된다)")
    void senderRole_isRequired(String role) {
        // when & then
        assertThatThrownBy(() -> command(roomId, senderId, role, "NORMAL"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("senderRole");
    }

    @ParameterizedTest(name = "messageType=[{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("messageType이 비면 거부 — 클라가 보내는 값이라 누락·공백이 실제로 들어온다")
    void messageType_isRequired(String type) {
        // when & then
        assertThatThrownBy(() -> command(roomId, senderId, "BUYER", type))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("messageType");
    }

    @Test
    @DisplayName("본문·닉네임·이메일·clientMsgId는 여기서 강제하지 않는다 — 그 결정은 전송 흐름 하나만 갖는다")
    void optionalFields_areNotGuardedHere() {
        // when & then: 여기서 같이 막으면 같은 규칙이 두 곳에 생겨 한쪽만 바뀌는 순간 어긋난다.
        // (clientMsgId 필수·64자 상한은 SendChatService가 소유한다)
        assertThatCode(() -> new SendChatCommand(roomId, senderId, "GUEST", false, true,
                null, null, "NORMAL", null, null))
                .doesNotThrowAnyException();
    }
}
