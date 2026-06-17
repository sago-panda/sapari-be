package com.sapari.chat.infrastructure.persistence.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.sapari.chat.domain.model.ChatMessage;
import com.sapari.chat.domain.model.ChatMessageType;
import com.sapari.chat.infrastructure.persistence.document.ChatMessageDocument;

/**
 * ChatMessage 도메인 ↔ ChatMessageDocument 영속 변환 (MapStruct).
 *
 * <p>평면 필드는 자동 매핑하고, sealed {@link ChatMessageType} ↔ String은 아래 default 메서드가 담당한다.
 * SYSTEM 메시지는 영속 대상이 아니므로(각 Pod 로컬 렌더 전용) 양방향 모두 거부한다.
 */
@Mapper(componentModel = "spring")
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
