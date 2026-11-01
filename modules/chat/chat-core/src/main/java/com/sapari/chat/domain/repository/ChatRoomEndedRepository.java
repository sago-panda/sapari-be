package com.sapari.chat.domain.repository;

import java.util.UUID;

import reactor.core.publisher.Mono;

/**
 * 방 종료 사실 포트 — live가 발행한 종료 이벤트를 chat이 받아 남겨두는 마커.
 *
 * <p><b>판정 권한은 chat에 없다.</b> 진실의 원천은 live(Postgres)이고, chat은 발행된 사실을 캐싱할 뿐이다.
 * 그래서 이 마커를 쓰는 곳은 종료 이벤트 핸들러 하나뿐이고, chat이 스스로 방을 끝내는 경로는 없다.
 *
 * <p><b>왜 필요한가</b>: 룸 토큰은 발급 시점에 라이브였다는 사실만 담고, 그 뒤 종료를 담지 못한다.
 * 종료 직전에 토큰을 받은 사람은 세션이 닫힌 뒤에도 토큰 만료 전까지 다시 접속할 수 있다.
 *
 * <p><b>어댑터 계약</b>: Redis 장애 시 {@code false}로 흡수하지 말고 <b>error를 전파</b>한다 —
 * "확실히 살아있음"과 "조회 불가"를 소비처가 구분할 수 있어야 한다. 소비처(입장 게이트) 정책은
 * 강퇴 조회와 같은 fail-open이다.
 */
public interface ChatRoomEndedRepository {

    /** 종료 사실을 남긴다. 같은 방에 여러 Pod가 동시에 불러도 안전하다(덮어쓰기). */
    Mono<Void> markEnded(UUID roomId);

    Mono<Boolean> isEnded(UUID roomId);
}
