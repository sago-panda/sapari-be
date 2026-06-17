package com.sapari.chat.domain.repository;

import java.util.UUID;

import reactor.core.publisher.Mono;

/**
 * 강퇴 여부 조회 포트 — Redis SET(kicked:{roomId}) 멤버십 검사.
 * 강퇴 등록·방 정리는 api-app(blocking)이 직접 처리하므로 streaming-app에선 읽기만 둔다.
 *
 * <p><b>어댑터 계약</b>: Redis 장애 시 {@code false}로 흡수하지 말고 <b>error를 전파</b>한다.
 * {@code false}(=비강퇴)로 삼키면 소비처가 "확실히 비강퇴"와 "조회 불가"를 구분할 수 없다.
 * <p><b>소비처(send 경로) 정책은 fail-open</b>: error는 전송 허용({@code onErrorReturn(false)})으로 매핑한다.
 * 가용성 우선(채팅 전면 불능이 강퇴자 일시 도배보다 더 나쁜 결과)이며, Redis 복구 후 다음 전송부터 정상 체크가 재개된다.
 */
public interface ChatKickRepository {

    Mono<Boolean> isKicked(UUID roomId, UUID userId);
}
