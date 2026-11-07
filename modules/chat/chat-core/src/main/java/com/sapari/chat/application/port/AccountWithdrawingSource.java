package com.sapari.chat.application.port;

import java.util.UUID;

import reactor.core.publisher.Flux;

/**
 * user 가 발행하는 탈퇴 신청 스트림 — {@code user:account:withdrawing} Redis Pub/Sub 채널(user 소유 계약).
 *
 * <p>payload 는 JSON {@code {"userId":"<uuid>","requestedAt":<epochMillis>}}. chat 이 쓰는 건 userId
 * 뿐이라 어댑터가 그것만 뽑아 흘려보낸다 — 형제 {@link LiveRoomEndedSource} 와 같은 모양이다.
 *
 * <p><b>왜 {@code ChatAccountEvent} 에 종류를 더하지 않는가.</b> 그 봉투는 {@code chat-core} 에 있어
 * user 가 그걸로 발행할 수 없다({@code X-core → Y-api ONLY}). 한때 그 타입 javadoc 에 "탈퇴도 같은
 * 길을 타고 종류만 하나 더한다" 고 적혀 있었고 <b>지킬 수 없는 약속이었다</b>. 그래서 채널과 구독이
 * 하나 늘고 타입은 모듈을 넘지 않는다.
 *
 * <p><b>왜 하드 삭제가 아니라 신청인가.</b> 탈퇴는 두 단계다 — 신청(상태 {@code WITHDRAWING},
 * 즉시)과 하드 삭제(배치, 유예 뒤). 신청 순간부터 계정이 활성이 아니므로 세션을 끊을 때도 그때다.
 * 삭제까지 기다리면 유예 기간 전체가 구멍으로 남는다.
 */
public interface AccountWithdrawingSource {

    Flux<UUID> withdrawing();
}
