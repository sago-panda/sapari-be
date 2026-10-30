package com.sapari.chat.infrastructure.persistence.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import com.sapari.chat.domain.model.ChatMessage;
import com.sapari.chat.domain.model.ChatMessageType;
import com.sapari.chat.infrastructure.persistence.document.ChatMessageDocument;

/**
 * ChatMessage 도메인 ↔ ChatMessageDocument 영속 변환 (MapStruct).
 *
 * <p>평면 필드는 자동 매핑하고, sealed {@link ChatMessageType} ↔ String은 아래 default 메서드가 담당한다.
 * SYSTEM 메시지는 영속 대상이 아니므로(각 Pod 로컬 렌더 전용) 양방향 모두 거부한다.
 *
 * <p><b>{@code unmappedTargetPolicy = ERROR}</b> — 도메인 record 에 필드를 더하고 이 매퍼를 안 고치면
 * 빌드를 깬다. 기본값({@code WARN})으로는 MapStruct 가 그 타깃을 비워 둔 채 경고 한 줄만 내고
 * 통과하는데(실측: {@code warning: Unmapped target property} + BUILD SUCCESSFUL), 그러면 그 필드가
 * 영속된 메시지를 읽을 때마다 조용히 null 이 된다.
 *
 * <p>반대 방향에는 이 그물이 필요 없다 — {@code toDocument}가 생성자를 위치 인자로 부르므로 도큐먼트에
 * 필드가 늘면 컴파일러가 먼저 막는다. 그물이 없는 쪽은 도메인 방향 하나다.
 *
 * <p>라운드트립 테스트로는 대신할 수 없다. 도메인에 필드가 늘면 {@code new ChatMessage(...)} 를 쓰는
 * 테스트가 전부 컴파일 실패하는데, 그걸 가장 손 덜 가게 고치는 방법이 새 인자에 {@code null} 을 넣는
 * 것이고 그러면 양쪽이 모두 null 이라 {@code isEqualTo} 가 통과한다(실측 — 라운드트립 4건 전부 통과,
 * record component 를 돌아 읽는 테스트만 실패). 즉 수정이 쉬운 쪽이 정확히 그물을 빠져나간다.
 *
 * <p>{@code LiveRoomMapper} 가 {@code IGNORE} 인 것과 어긋나지 않는다 — 그쪽 엔티티에는 도메인에 대응이
 * 없는 영속 전용 필드가 많아 비는 타깃이 정상이다. 여기 두 타입은 필드가 일대일이라 비는 타깃이 곧 누락이다.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ChatMessageMapper {

    @Mapping(target = "type", expression = "java(toType(document.getType()))")
    ChatMessage toDomain(ChatMessageDocument document);

    default ChatMessageDocument toDocument(ChatMessage message) {
        return new ChatMessageDocument(
                message.id(), message.roomId(), message.senderId(), message.senderNickname(),
                message.senderEmail(), message.senderRole().name(), toTypeName(message.type()),
                message.originalMessage(), message.displayMessage(), message.clientMsgId(),
                message.createdAt());
    }

    default ChatMessageType toType(String type) {
        return switch (type) {
            case "NORMAL" -> new ChatMessageType.Normal();
            case "NOTICE" -> new ChatMessageType.Notice();
            default -> throw new IllegalArgumentException("영속될 수 없는 메시지 type: " + type);
        };
    }

    default String toTypeName(ChatMessageType type) {
        return switch (type) {
            case ChatMessageType.Normal normal -> "NORMAL";
            case ChatMessageType.Notice notice -> "NOTICE";
            case ChatMessageType.System system ->
                    throw new IllegalArgumentException("SYSTEM 메시지는 영속하지 않는다 — 각 Pod 로컬 렌더 전용");
        };
    }
}
