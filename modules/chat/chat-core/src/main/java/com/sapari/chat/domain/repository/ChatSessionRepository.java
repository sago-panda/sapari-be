package com.sapari.chat.domain.repository;

import java.time.Duration;
import java.util.UUID;

import com.sapari.chat.port.ReactiveChat;

import reactor.core.publisher.Mono;

/**
 * 크로스 Pod 세션 집계 포트 — Redis HASH({@code chat:room:{roomId}:sessions}) 어댑터.
 * 실제 WS 채널 보유는 로컬 메모리 레지스트리가 맡고, 여기선 sessionId→userId 매핑만 둔다.
 */
@ReactiveChat
public interface ChatSessionRepository {

    /**
     * 세션 등재. 등재와 TTL을 한 번에 처리한다.
     *
     * <p><b>어댑터 계약</b>: Redis 장애를 흡수하지 말고 <b>error를 전파</b>한다. 접속을 허용할지는 호출자
     * 정책이고, 여기서 삼키면 "등재됨"과 "등재 실패"를 구분할 수 없다.
     */
    Mono<Void> add(UUID roomId, String sessionId, UUID userId);

    /**
     * 이 세션이 아직 살아 있다고 알린다 — <b>재등록(upsert)이고 TTL 터치가 아니다</b>.
     *
     * <p>터치만 하면 Redis 데이터 유실(replica 승격 등) 뒤에 사라진 항목이 되살아나지 않아 그 방송
     * 내내 시청자 수가 모자란다. 등재와 같은 쓰기를 다시 하므로 항목이 없으면 다시 생긴다.
     *
     * <p><b>이 호출을 {@link #heartbeatInterval()} 보다 드물게 하면 그 세션은 집계에서 빠진다.</b>
     * 반대로 자주 하는 것은 비용만이고 무해하다.
     *
     * <p>실패는 전파한다 — 호출자가 "한 번 실패해도 다음 주기가 받아 준다" 를 책임진다. 여기서
     * 삼키면 전 세션의 재등록이 조용히 멈춘 상태와 정상이 구분되지 않는다.
     */
    Mono<Void> heartbeat(UUID roomId, String sessionId, UUID userId);

    /**
     * 재등록 주기 — 이 간격보다 드물게 {@link #heartbeat} 하면 집계에서 빠진다.
     *
     * <p>스케줄링 정책이 아니라 <b>영속 계약</b>이라 포트에 있다. 항목이 언제 낡은 것으로 취급되는지를
     * 정하는 쪽이 이 어댑터이므로, "얼마나 자주 재등록해야 하는가" 의 답도 같은 자리에 있어야 한다 —
     * 두 곳에 두면 한쪽을 바꿀 때 다른 쪽이 따라가지 않고, 그 결과가 <b>살아 있는 시청자가 집계에서
     * 사라지는 것</b>이다.
     */
    Duration heartbeatInterval();

    Mono<Void> remove(UUID roomId, String sessionId);

    /**
     * 고유 유저 수(distinct userId) — 같은 유저 멀티탭은 1로 집계.
     *
     * <p><b>낡은 항목은 세지 않는다.</b> Pod 가 크래시하면 그 Pod 의 세션은 해제 경로를 타지 못해
     * 명부에 남는데, 그걸 그대로 세면 그 방의 시청자 수가 방송 내내 부풀어 있다. 마지막 재등록이
     * 너무 오래된 항목은 집계에서 빼고, 치우는 것은 TTL 과 방 종료가 맡는다.
     */
    Mono<Long> count(UUID roomId);

    Mono<Void> clearRoom(UUID roomId);
}
