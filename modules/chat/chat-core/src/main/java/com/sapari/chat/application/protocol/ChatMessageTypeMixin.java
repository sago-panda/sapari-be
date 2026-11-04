package com.sapari.chat.application.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import com.sapari.chat.domain.model.ChatMessageType;

/**
 * {@link ChatMessageType}(sealed) 폴리모픽 직렬화 계약 — 도메인 모델에 Jackson을 바르지 않기 위한 MixIn.
 *
 * <p><b>등록 의무</b>: ChatEnvelope(ChatMsg)를 직렬화/역직렬화하는 <b>모든 ObjectMapper</b>는
 * {@code objectMapper.addMixIn(ChatMessageType.class, ChatMessageTypeMixin.class)} 등록이 필수다
 * (브로드캐스터 어댑터 포함). 누락 시 역직렬화가 InvalidDefinitionException으로 즉사한다
 * (조용한 오염은 아님 — 첫 subscribe에서 시끄럽게 실패).
 *
 * <p>와이어에는 NORMAL/NOTICE만 실린다(SYSTEM은 미영속·각 Pod 로컬 렌더 전용) —
 * SYSTEM 매핑은 sealed 전수성 차원의 방어적 포함.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ChatMessageType.Normal.class, name = "NORMAL"),
        @JsonSubTypes.Type(value = ChatMessageType.Notice.class, name = "NOTICE"),
        @JsonSubTypes.Type(value = ChatMessageType.System.class, name = "SYSTEM")
})
public interface ChatMessageTypeMixin {
}
