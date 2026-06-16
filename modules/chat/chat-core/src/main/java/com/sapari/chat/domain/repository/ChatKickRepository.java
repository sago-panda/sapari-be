package com.sapari.chat.domain.repository;

import java.util.UUID;

import reactor.core.publisher.Mono;

/**
 * 강퇴 여부 조회 포트 — Redis SET(kicked:{roomId}) 멤버십 검사.
 * 강퇴 등록·방 정리는 api-app(blocking)이 직접 처리하므로 streaming-app에선 읽기만 둔다.
 *
 * <p><b>⚠️ fail-closed 계약</b>: Redis 장애 시 error를 전파한다(false로 흡수 금지). 소비처는 error를
 * 연결/전송 거부로 매핑해야 한다 — false로 처리하면 강퇴된 사용자가 Redis 순단 중 재접속한다.
 */
public interface ChatKickRepository {

    Mono<Boolean> isKicked(UUID roomId, UUID userId);
}
