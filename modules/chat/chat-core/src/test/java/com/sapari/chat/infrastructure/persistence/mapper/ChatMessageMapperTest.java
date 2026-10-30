package com.sapari.chat.infrastructure.persistence.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import com.sapari.chat.domain.model.ChatConstants;
import com.sapari.chat.domain.model.ChatMessage;
import com.sapari.chat.domain.model.ChatMessageType;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.infrastructure.persistence.document.ChatMessageDocument;

class ChatMessageMapperTest {

    private final ChatMessageMapper mapper = Mappers.getMapper(ChatMessageMapper.class);

    @Test
    @DisplayName("domain → document → domain round-trip — 전 필드·sealed type 보존")
    void round_trips_all_fields() {
        // given
        ChatMessage message = new ChatMessage(
                "65a1f2c3d4e5f60718293a4b",
                UUID.randomUUID(), UUID.randomUUID(),
                "구매자닉", "buyer@example.com",
                ChatRole.BUYER, new ChatMessageType.Normal(),
                "원문", "마스킹본", "client-1",
                Instant.parse("2026-06-12T00:00:00Z"));

        ChatMessage back = mapper.toDomain(mapper.toDocument(message));

        // when & then
        assertThat(back).isEqualTo(message);
    }

    @Test
    @DisplayName("NOTICE type round-trip")
    void notice_round_trips() {
        // given
        ChatMessage notice = new ChatMessage(
                null, UUID.randomUUID(), UUID.randomUUID(),
                "판매자닉", "seller@example.com",
                ChatRole.SELLER, new ChatMessageType.Notice(),
                "공지", "공지", null, Instant.now());

        ChatMessageDocument document = mapper.toDocument(notice);

        // when & then
        assertThat(document.getType()).isEqualTo("NOTICE");
        assertThat(mapper.toDomain(document).type()).isInstanceOf(ChatMessageType.Notice.class);
    }

    @Test
    @DisplayName("SYSTEM 메시지 영속 시도 — 거부 (각 Pod 로컬 렌더 전용)")
    void system_message_is_rejected_on_persist() {
        // given
        ChatMessage system = new ChatMessage(
                null, UUID.randomUUID(), ChatConstants.SYSTEM_SENDER_ID,
                "SYSTEM", null,
                ChatRole.SYSTEM, new ChatMessageType.System("KICKED"),
                null, "강퇴되었습니다", null, Instant.now());

        // when & then
        assertThatThrownBy(() -> mapper.toDocument(system))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SYSTEM");
    }

    @Test
    @DisplayName("영속될 수 없는 type 문자열 복원 시도 — 거부")
    void unknown_type_string_is_rejected() {
        // given
        ChatMessageDocument corrupted = new ChatMessageDocument(
                "65a1f2c3d4e5f60718293a4b", UUID.randomUUID(), UUID.randomUUID(),
                "닉", null, "BUYER", "SYSTEM", null, "본문", null, Instant.now());

        // when & then
        assertThatThrownBy(() -> mapper.toDomain(corrupted))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("⭐ toDomain 이 도메인 record 의 모든 참조형 필드를 채운다 — 새 필드가 조용히 비지 않는다")
    void maps_every_domain_component() throws Exception {
        // given: 전 필드에 값이 있는 도큐먼트 — 비어 있는 타깃은 도큐먼트가 아니라 매퍼 탓이어야 한다
        ChatMessageDocument document = new ChatMessageDocument(
                "65a1f2c3d4e5f60718293a4b", UUID.randomUUID(), UUID.randomUUID(),
                "구매자닉", "buyer@example.com", "BUYER", "NORMAL",
                "원문", "마스킹본", "client-1", Instant.parse("2026-06-12T00:00:00Z"));

        // when
        ChatMessage domain = mapper.toDomain(document);

        // then: 필드를 열거하지 않고 record component 를 돌아 읽는다. 열거하면 도메인에 필드를 더할 때
        // 이 테스트도 같이 안 고쳐져서, 막으려던 바로 그 실수에 테스트가 함께 눈을 감는다.
        //
        // 이것이 잡는 것: 도메인 record 에 필드를 더하고 매퍼를 안 고치면 MapStruct 는 그 타깃을 비워 두고
        // 경고만 낸다(실측 — "warning: Unmapped target property" + BUILD SUCCESSFUL). 도큐먼트 쪽은
        // toDocument 가 위치 인자로 생성자를 부르므로 컴파일러가 막지만, 도메인 쪽에는 그 그물이 없다.
        //
        // 한계: 원시형 필드는 못 잡는다. int 를 더하면 미매핑 타깃이 0 으로 남는데 isNotNull 로는
        // "안 채워졌다" 와 "타입 기본값으로 채워졌다" 가 구분되지 않는다(실측 — int 필드를 더하면
        // 경고만 나고 이 테스트도 통과한다). 그쪽은 매퍼의 unmappedTargetPolicy = ERROR 가 잡고,
        // 그 속성이 지워지는 것은 ArchitectureTest 의 ArchUnit 규칙이 잡는다.
        //
        // 이 중 다섯(roomId·senderId·senderRole·type·createdAt)은 ChatMessage 컴팩트 생성자가 null 을
        // 거부하므로 미매핑이면 단언에 닿기 전에 생성이 던진다. 이 순회가 새로 보는 것은 나머지 여섯과
        // 앞으로 더해질 필드다.
        for (RecordComponent component : ChatMessage.class.getRecordComponents()) {
            assertThat(component.getAccessor().invoke(domain))
                    .as("ChatMessage.%s — toDomain 이 채우지 않았다", component.getName())
                    .isNotNull();
        }
    }
}
