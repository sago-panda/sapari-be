package com.sapari.chat.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.chat.view.ChatMessageView;

@DisplayName("ChatMessage view 변환 — PII(senderEmail·원문) 게이팅")
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
}
