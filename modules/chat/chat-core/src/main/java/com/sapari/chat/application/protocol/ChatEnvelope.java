package com.sapari.chat.application.protocol;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import com.sapari.chat.domain.model.ChatMessage;

/**
 * chat:pubsub 채널 봉투 — CHAT(메시지)와 KICK_EVENT(강퇴 신호)를 한 채널에서 구분한다.
 *
 * <p>식별자는 {@code kind}를 쓴다(ChatMessage.type의 NORMAL|NOTICE와 이름 충돌 방지).
 * ChatBroadcaster(application/port)가 반환 타입으로 노출하므로 application/protocol에 둔다 —
 * infrastructure에 두면 포트(application)가 infrastructure를 참조하는 방향 역전(ArchUnit application↛infra 위반).
 * ChatMessage(domain)만 참조해 순환은 없고, 직렬화 로직은 infrastructure 어댑터(RedisChatBroadcaster)에 남는다.
 *
 * <p><b>양쪽 다 이 타입으로 직렬화한다.</b> CHAT은 리액티브 브로드캐스터가, KICK_EVENT는 블로킹
 * 발행 어댑터가 각자 만들지만 둘 다 여기 붙은 계약을 그대로 쓴다. 한쪽이 JSON을 손으로 지으면
 * 필드명 한 글자가 틀려도 빌드가 통과하고 그 봉투는 전 Pod에서 조용히 사라진다 — 실제 타입을
 * 직렬화하면 그 일치가 사람이 지킬 약속이 아니라 컴파일러가 지키는 것이 된다.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ChatEnvelope.ChatMsg.class, name = "CHAT"),
        @JsonSubTypes.Type(value = ChatEnvelope.KickEvent.class, name = "KICK_EVENT")
})
public sealed interface ChatEnvelope permits ChatEnvelope.ChatMsg, ChatEnvelope.KickEvent {

    record ChatMsg(ChatMessage message) implements ChatEnvelope { }   // kind=CHAT

    /**
     * kind=KICK_EVENT.
     *
     * <p><b>null 을 거부한다.</b> 이 채널은 신뢰경계 밖이다 — 같은 어댑터의 채널명 파싱이 스스로
     * "여기 오는 건 우리 발행이 아니다"라고 적어 두었다. 그리고 봉투 매퍼가 롤링 배포를 위해 모르는
     * 필드를 흘려보내게 된 뒤로, 필드명이 어긋난 강퇴 봉투는 <b>거부가 아니라 {@code userId=null}</b>
     * 이 된다(실측: {@code userIdd} 오타가 {@code KickEvent[userId=null]} 로 통과했다).
     *
     * <p>통과하면 둘이 동시에 일어난다. 강퇴 팬아웃이 이 값으로 당사자를 고르므로 <b>아무 세션도
     * 끊지 못하고</b>(조치가 조용히 no-op), 동시에 {@code userId} 키가 빠진 KICK 프레임이 방
     * 전원에게 나간다. 로그도 남지 않는다 — 모더레이션이 소리 없이 실패하는 방향이다.
     *
     * <p>{@code ChatMsg} 쪽은 {@code ChatMessage} 의 필수 필드 검증이 같은 일을 한다. 이쪽만 비어
     * 있었다.
     */
    record KickEvent(UUID userId) implements ChatEnvelope {
        public KickEvent {
            if (userId == null) {
                throw new IllegalArgumentException("KICK_EVENT 의 userId 는 필수입니다.");
            }
        }
    }
}
