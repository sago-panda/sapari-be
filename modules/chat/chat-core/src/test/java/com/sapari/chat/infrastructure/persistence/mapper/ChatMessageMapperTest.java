package com.sapari.chat.infrastructure.persistence.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import com.sapari.chat.domain.model.ChatMessage;
import com.sapari.chat.domain.model.ChatMessageType;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.infrastructure.persistence.document.ChatMessageDocument;

class ChatMessageMapperTest {

    private final ChatMessageMapper mapper = Mappers.getMapper(ChatMessageMapper.class);

    @Test
    @DisplayName("domain → document → domain round-trip — 전 필드·sealed type 보존")
    void round_trips_all_fields() {
        ChatMessage message = new ChatMessage(
                "65a1f2c3d4e5f60718293a4b",
                UUID.randomUUID(), UUID.randomUUID(),
                "구매자닉", "buyer@example.com",
                ChatRole.BUYER, new ChatMessageType.Normal(),
                "원문", "마스킹본", "client-1",
                Instant.parse("2026-06-12T00:00:00Z"));

        ChatMessage back = mapper.toDomain(mapper.toDocument(message));

        assertThat(back).isEqualTo(message);
    }

    @Test
    @DisplayName("NOTICE type round-trip")
    void notice_round_trips() {
        ChatMessage notice = new ChatMessage(
                null, UUID.randomUUID(), UUID.randomUUID(),
                "판매자닉", "seller@example.com",
                ChatRole.SELLER, new ChatMessageType.Notice(),
                "공지", "공지", null, Instant.now());

        ChatMessageDocument document = mapper.toDocument(notice);

        assertThat(document.getType()).isEqualTo("NOTICE");
        assertThat(mapper.toDomain(document).type()).isInstanceOf(ChatMessageType.Notice.class);
    }

    @Test
    @DisplayName("SYSTEM 메시지 영속 시도 — 거부 (각 Pod 로컬 렌더 전용)")
    void system_message_is_rejected_on_persist() {
        ChatMessage system = new ChatMessage(
                null, UUID.randomUUID(), UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "SYSTEM", null,
                ChatRole.SYSTEM, new ChatMessageType.System("KICKED"),
                null, "강퇴되었습니다", null, Instant.now());

        assertThatThrownBy(() -> mapper.toDocument(system))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SYSTEM");
    }

    @Test
    @DisplayName("영속될 수 없는 type 문자열 복원 시도 — 거부")
    void unknown_type_string_is_rejected() {
        ChatMessageDocument corrupted = new ChatMessageDocument(
                "65a1f2c3d4e5f60718293a4b", UUID.randomUUID(), UUID.randomUUID(),
                "닉", null, "BUYER", "SYSTEM", null, "본문", null, Instant.now());

        assertThatThrownBy(() -> mapper.toDomain(corrupted))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
