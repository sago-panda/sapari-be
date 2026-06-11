package com.sapari.chat.infrastructure.messaging;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import com.sapari.chat.domain.model.ChatMessage;

/**
 * chat:pubsub 채널 봉투 — CHAT(메시지)와 KICK_EVENT(강퇴 신호)를 한 채널에서 구분한다.
 *
 * <p>식별자는 {@code kind}를 쓴다(ChatMessage.type의 NORMAL|NOTICE와 이름 충돌 방지).
 * chat-api에 두면 ChatMessage(chat-core) 참조로 순환이 생겨 chat-core/infrastructure/messaging에 둔다.
 *
 * <p>CHAT 봉투는 streaming-app 내부(broadcaster publish/subscribe)에서만 직렬화/역직렬화돼 동일
 * ObjectMapper가 보장된다. KICK_EVENT는 api-app이 이 계약과 바이트 단위로 일치하는 JSON을 손수 발행한다
 * (UUID 단일 필드라 ObjectMapper 설정 차이의 영향을 받지 않는다).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ChatEnvelope.ChatMsg.class, name = "CHAT"),
        @JsonSubTypes.Type(value = ChatEnvelope.KickEvent.class, name = "KICK_EVENT")
})
public sealed interface ChatEnvelope permits ChatEnvelope.ChatMsg, ChatEnvelope.KickEvent {

    record ChatMsg(ChatMessage message) implements ChatEnvelope { }   // kind=CHAT

    record KickEvent(UUID userId) implements ChatEnvelope { }         // kind=KICK_EVENT
}
