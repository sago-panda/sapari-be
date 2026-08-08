package com.sapari.chat.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * SYSTEM 메시지는 본문을 싣지 않는다 — 와이어에 나가는 건 {@code code}뿐이고 표시 문구는 클라이언트가
 * 그 code로 만든다. 그래서 code가 비어 있으면 클라는 "무슨 일이 일어났는지 알 수 없는" 프레임을 받고
 * 아무것도 렌더하지 못한다. 여기서 막지 않으면 그 상태가 조용히 흘러간다.
 */
@DisplayName("ChatMessageType — SYSTEM은 code 없이 만들어지지 않는다")
class ChatMessageTypeTest {

    @ParameterizedTest(name = "code=[{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("code가 비면 거부 — 공백도 포함(클라가 렌더할 근거가 사라진다)")
    void systemRequiresCode(String code) {
        // when & then
        assertThatThrownBy(() -> new ChatMessageType.System(code))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("code");
    }

    @Test
    @DisplayName("code가 있으면 생성되고 그대로 보관된다")
    void systemKeepsCode() {
        // when
        ChatMessageType.System type = new ChatMessageType.System("ROOM_ENDED");

        // then
        assertThat(type.code()).isEqualTo("ROOM_ENDED");
    }

    @Test
    @DisplayName("NORMAL·NOTICE는 값이 없는 종류라 서로 같다 — 인스턴스를 비교 키로 쓸 수 있다")
    void valuelessTypesAreEqual() {
        // when & then: record라 동치성이 값 기반이다. 매번 새로 만들어 넘겨도 같은 종류로 취급된다는
        // 뜻이고, 정책 판정이 인스턴스 동일성에 기대지 않아도 된다는 근거다.
        assertThat(new ChatMessageType.Normal()).isEqualTo(new ChatMessageType.Normal());
        assertThat(new ChatMessageType.Notice()).isEqualTo(new ChatMessageType.Notice());
        assertThat(new ChatMessageType.Normal()).isNotEqualTo(new ChatMessageType.Notice());
    }

    @Test
    @DisplayName("code가 다르면 다른 SYSTEM — 강퇴와 종료가 같은 값으로 뭉개지지 않는다")
    void systemTypesDifferByCode() {
        // when & then
        assertThat(new ChatMessageType.System("KICKED"))
                .isNotEqualTo(new ChatMessageType.System("ROOM_ENDED"));
    }
}
