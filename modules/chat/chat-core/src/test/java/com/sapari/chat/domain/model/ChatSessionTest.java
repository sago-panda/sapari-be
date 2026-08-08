package com.sapari.chat.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * ChatSession은 접속 이후 모든 권한 판정의 근거다 — 여기 담긴 role·isRoomOwner가 NOTICE 발신 권한,
 * 이메일 노출, 레이트리밋 면제를 전부 결정한다. 그래서 모순된 인스턴스가 아예 만들어지지 않는 것이
 * 마지막 방어선이고, 이 파일이 그 방어선을 고정한다.
 */
@DisplayName("ChatSession 불변식 — 모순된 세션은 만들어지지 않는다")
class ChatSessionTest {

    private final UUID roomId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID userId = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    @DisplayName("roomId 없음 → 거부(어느 방인지 모르는 세션은 방 격리를 무너뜨린다)")
    void roomId_isRequired() {
        // when & then
        assertThatThrownBy(() -> new ChatSession(null, userId, ChatRole.BUYER, "닉", "e@example.com", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("roomId");
    }

    @Test
    @DisplayName("userId 없음 → 거부(발신자를 특정할 수 없으면 강퇴·레이트리밋이 성립하지 않는다)")
    void userId_isRequired() {
        // when & then
        assertThatThrownBy(() -> new ChatSession(roomId, null, ChatRole.BUYER, "닉", "e@example.com", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");
    }

    @Test
    @DisplayName("role 없음 → 거부(권한 판정의 첫 축이 비면 정책이 판단할 근거가 없다)")
    void role_isRequired() {
        // when & then
        assertThatThrownBy(() -> new ChatSession(roomId, userId, null, "닉", "e@example.com", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("role");
    }

    @ParameterizedTest(name = "role={0}")
    @EnumSource(value = ChatRole.class, names = {"BUYER", "GUEST", "ADMIN"})
    @DisplayName("SELLER가 아닌데 방 주인 → 거부(토큰 클레임이 어긋나도 여기서 막힌다)")
    void nonSeller_cannotOwnRoom(ChatRole role) {
        // when & then: 방 소유는 방 단위 권한이고 소유자는 반드시 그 방을 여는 SELLER다.
        // 이게 뚫리면 NOTICE 발신과 구매자 이메일 열람이 함께 열린다.
        assertThatThrownBy(() -> new ChatSession(roomId, userId, role, "닉", "e@example.com", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("isRoomOwner");
    }

    @Test
    @DisplayName("SELLER + 방 주인 → 허용(정상 진행자)")
    void seller_canOwnRoom() {
        // when
        ChatSession session = new ChatSession(roomId, userId, ChatRole.SELLER, "판매자", "s@example.com", true);

        // then
        assertThat(session.isRoomOwner()).isTrue();
        assertThat(session.role()).isEqualTo(ChatRole.SELLER);
    }

    @Test
    @DisplayName("SELLER인데 방 주인 아님 → 허용(판매자도 남의 방엔 시청자로 들어온다)")
    void seller_canBeVisitor() {
        // when & then
        assertThatCode(() -> new ChatSession(roomId, userId, ChatRole.SELLER, "판매자", "s@example.com", false))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("GUEST는 닉네임·이메일이 없어도 된다 — 있는지 없는지로 신원을 강제하지 않는다")
    void guest_allowsNullIdentityFields() {
        // when
        ChatSession session = new ChatSession(roomId, userId, ChatRole.GUEST, null, null, false);

        // then: 게스트는 에페메랄 id만 갖는다. 여기서 nickname을 필수로 만들면 비회원 시청이 막힌다
        assertThat(session.nickname()).isNull();
        assertThat(session.email()).isNull();
    }
}
