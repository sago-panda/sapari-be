package com.sapari.chat.domain.repository;

import java.util.UUID;

import reactor.core.publisher.Mono;

/**
 * 강퇴 여부 조회 포트 — Redis SET(kicked:{roomId}) 멤버십 검사.
 * 강퇴 등록·방 정리는 api-app(blocking)이 직접 처리하므로 streaming-app에선 읽기만 둔다.
 */
public interface ChatKickRepository {

    Mono<Boolean> isKicked(UUID roomId, UUID userId);
}
