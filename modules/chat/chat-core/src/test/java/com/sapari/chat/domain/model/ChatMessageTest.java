package com.sapari.chat.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.time.Instant;
import java.util.stream.Stream;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.sapari.chat.view.ChatMessageView;

@DisplayName("ChatMessage — 불변식과 view 변환(PII 게이팅)")
class ChatMessageTest {

    private ChatMessage sample() {
        return ChatMessage.builder()
                .id("obj1")
                .roomId(UUID.randomUUID())
                .senderId(UUID.randomUUID())
                .senderNickname("구매자1")
                .senderEmail("buyer@sapari.com")
                .senderRole(ChatRole.BUYER)
                .type(new ChatMessageType.Normal())
                .originalMessage("시발 안녕")
                .displayMessage("*** 안녕")
                .clientMsgId("c1")
                .createdAt(Instant.now())
                .build();
    }

    @Test
    @DisplayName("toView(): senderEmail·원문 제외(누출 방지 기본), 마스킹 본문·닉네임은 노출")
    void toView_excludes_pii() {
        // when
        ChatMessageView view = sample().toView();

        // then — PII는 제외
        assertThat(view.senderEmail()).isNull();
        assertThat(view.originalMessage()).isNull();
        // PII 아닌 노출 필드는 그대로
        assertThat(view.displayMessage()).isEqualTo("*** 안녕");
        assertThat(view.senderNickname()).isEqualTo("구매자1");
    }

    @Test
    @DisplayName("toOwnerView(): 방 주인 수신자에게 senderEmail·원문 포함")
    void toOwnerView_includes_pii() {
        // when
        ChatMessageView view = sample().toOwnerView();

        // then — 방 주인만 받는 PII 포함
        assertThat(view.senderEmail()).isEqualTo("buyer@sapari.com");
        assertThat(view.originalMessage()).isEqualTo("시발 안녕");
        assertThat(view.displayMessage()).isEqualTo("*** 안녕");
    }

    // ── 불변식 ──
    // 종류(NORMAL/NOTICE/SYSTEM)와 무관하게 항상 성립해야 하는 것만 여기서 막는다.
    // 본문·닉네임·이메일은 종류마다 유무가 달라 여기 두면 SYSTEM 렌더가 통째로 막힌다.

    @ParameterizedTest(name = "{0} 없음")
    @MethodSource("missingRequiredFields")
    @DisplayName("필수 필드가 비면 생성 자체가 막힌다 — 반쯤 빈 메시지가 Mongo까지 흘러가면 되돌릴 수 없다")
    void requiredFields(String field, ChatMessage.ChatMessageBuilder builder) {
        // when & then
        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(field);
    }

    static Stream<Arguments> missingRequiredFields() {
        return Stream.of(
                arguments("roomId", base().roomId(null)),
                arguments("senderId", base().senderId(null)),
                arguments("senderRole", base().senderRole(null)),
                arguments("type", base().type(null)),
                arguments("createdAt", base().createdAt(null))
        );
    }

    private static ChatMessage.ChatMessageBuilder base() {
        return ChatMessage.builder()
                .roomId(UUID.randomUUID())
                .senderId(UUID.randomUUID())
                .senderRole(ChatRole.BUYER)
                .type(new ChatMessageType.Normal())
                .displayMessage("안녕")
                .createdAt(Instant.now());
    }

    @Test
    @DisplayName("id는 없어도 된다 — 영속 전 메시지는 아직 ObjectId를 받지 못했다")
    void idIsOptionalBeforePersist() {
        // when
        ChatMessage message = base().build();

        // then
        assertThat(message.id()).isNull();
    }

    @ParameterizedTest(name = "{1} → \"{0}\"")
    @MethodSource("typeNames")
    @DisplayName("view의 type 문자열은 sealed 종류마다 하나씩 고정 — 와이어 계약이라 오타가 곧 클라 렌더 실패다")
    void typeNameOnView(String expected, ChatMessageType type) {
        // when
        ChatMessageView view = base().type(type).build().toView();

        // then
        assertThat(view.type()).isEqualTo(expected);
    }

    static Stream<Arguments> typeNames() {
        return Stream.of(
                arguments("NORMAL", new ChatMessageType.Normal()),
                arguments("NOTICE", new ChatMessageType.Notice()),
                arguments("SYSTEM", new ChatMessageType.System("ROOM_ENDED"))
        );
    }

    @Test
    @DisplayName("원문이 없으면 방주인 view에도 원문이 없다 — 없는 값을 만들어내지 않는다")
    void ownerViewWithoutOriginal() {
        // given: 마스킹이 걸리지 않아 원문을 따로 두지 않은 메시지
        ChatMessage message = base().displayMessage("안녕").originalMessage(null).build();

        // when
        ChatMessageView view = message.toOwnerView();

        // then
        assertThat(view.originalMessage()).isNull();
        assertThat(view.displayMessage()).isEqualTo("안녕");
    }
}
