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
 * <p><b>이 타입은 chat 안의 표현이지 도메인 간 계약이 아니다.</b> chat-core에 있으므로 다른 도메인이
 * 이걸로 발행할 수 없다 — 모듈 규칙이 {@code X-core → Y-api ONLY}이고 {@code -core}는 남이 의존하지
 * 않는다. 한때 여기에 "탈퇴도 같은 길을 타고 종류만 하나 더한다"고 적혀 있었는데 <b>지킬 수 없는
 * 약속이었다.</b> 탈퇴를 발행하는 것은 user 도메인이고, 그쪽은 이 타입에 닿지 못한다.
 *
 * <p>그때 따를 모양은 {@code live:room:ended}가 이미 보여 준다 — <b>와이어 모양은 발행자 것이고 구독자가
 * 적응한다.</b> user가 자기 채널에 자기 모양으로 발행하고, chat이 어댑터 하나를 더해 이 타입으로
 * 흡수한다. 채널과 구독이 하나 늘지만 타입이 모듈을 넘지 않는다. 봉투를 {@code chat-api}로 올리는 길도
 * 있으나 그러면 DTO·인터페이스만 두는 모듈이 와이어 포맷 책임을 갖게 되고, JSON을 손으로 짓는 길은
 * 이 저장소가 이미 배제했다.
 *
 * <p>{@code kind}는 그래서 <b>chat이 발행하는 것들</b>을 가른다. 지금은 밴 하나뿐이다.
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
