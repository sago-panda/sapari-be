package com.sapari.chat.application.protocol;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 계정 하나에 대한 조치를 전 Pod에 알리는 봉투 — 방이 아니라 <b>사람</b>이 단위다.
 *
 * <p><b>왜 {@code chat:pubsub}에 얹지 않는가.</b> 그 채널은 방 단위이고, 구독 어댑터가 채널명 접미를
 * {@code UUID}로 파싱해 라우팅한다. 방이 아닌 것을 그 이름 공간에 넣으면 전 Pod가 이벤트마다 파싱 실패
 * 로그를 찍고 봉투는 버려진다. 그래서 채널을 따로 둔다.
 *
 * <p><b>왜 사용자별 채널이 아닌가.</b> 어느 Pod에 그 사람의 세션이 있는지 발행하는 쪽이 모르므로 결국
 * 모든 Pod가 받아야 하고, 사용자별로 쪼개면 패턴 구독이 되어 비용은 같은데 파싱 함정만 늘어난다.
 * 한 채널에 싣고 받는 쪽이 "내게 그 사람의 세션이 있는가"로 거른다.
 *
 * <p>{@code kind}로 종류를 가른다. 지금은 밴 하나뿐이지만 <b>탈퇴</b>가 같은 길을 타야 한다 — 탈퇴한
 * 사람의 열린 세션도 끊어야 하고, 그 신호가 도달해야 하는 곳이 여기와 정확히 같다. 그때 채널도 구독도
 * 새로 만들지 않고 종류만 하나 더한다.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ChatAccountEvent.Banned.class, name = "BANNED")
})
public sealed interface ChatAccountEvent permits ChatAccountEvent.Banned {

    /** 이 사람의 열린 세션을 어느 방에서든 끊어야 한다. */
    record Banned(UUID userId) implements ChatAccountEvent { }   // kind=BANNED

    UUID userId();
}
