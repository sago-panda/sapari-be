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
 * <p>그 속성이 지워지는 것은 {@code ArchitectureTest} 의 ArchUnit 규칙이 막는다 — 지우는 시점에 미매핑
 * 필드가 없으면 빌드도 테스트도 멀쩡하기 때문이다. {@code @Mapper} 는 {@code RetentionPolicy.CLASS} 라
 * 런타임 리플렉션으로는 못 읽지만 클래스 파일에는 남고, 속성을 지운 경우 ArchUnit 이 기본값
 * {@code WARN} 을 채워 주므로 격하와 삭제가 같은 규칙에 걸린다.
 *
 * <p>반대 방향의 그물은 <b>생성자 인자가 늘 때만</b> 있다 — {@code toDocument}가 위치 인자로 부르므로
 * 그때는 컴파일러가 막는다. 도큐먼트에 생성자 밖으로 필드를 더하면 {@code unmappedSourcePolicy} 기본값이
 * {@code IGNORE} 라 경고도 에러도 없이 통과하고 {@code toDocument}는 그 필드를 영구히 쓰지 않는다.
 * 지금 그 일이 안 일어나는 것은 도큐먼트의 모든 필드가 {@code final} 이라 생성자를 지나야 하기 때문이고,
 * 그건 규칙이 아니라 스타일이다.
 *
 * <p>그리고 이 매퍼는 <b>영속에서 읽어 올리는 쪽만</b> 본다. 송신 경로의 생산자는
 * {@code SendChatService} 의 {@code ChatMessage.builder()} 라, 도메인에 필드를 더해도 그 경로는
 * 컴파일이 깨지지 않고 새 필드가 비어서 나간다. 여기 그물이 있다고 그쪽까지 덮이는 것은 아니다.
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
