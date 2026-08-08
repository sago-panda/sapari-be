package com.sapari.chat.domain.repository;

import java.util.UUID;

import reactor.core.publisher.Mono;

/**
 * 강퇴 SET(kicked:{roomId}) 포트 — 멤버십 검사와 방 단위 정리.
 * 강퇴 등록(SADD)은 api-app(blocking) 책임이라 여기 두지 않는다.
 *
 * <p><b>어댑터 계약</b>: Redis 장애 시 {@code false}로 흡수하지 말고 <b>error를 전파</b>한다.
 * {@code false}(=비강퇴)로 삼키면 소비처가 "확실히 비강퇴"와 "조회 불가"를 구분할 수 없다.
 * <p><b>소비처(send 경로) 정책은 fail-open</b>: error는 전송 허용({@code onErrorReturn(false)})으로 매핑한다.
 * 가용성 우선(채팅 전면 불능이 강퇴자 일시 도배보다 더 나쁜 결과)이며, Redis 복구 후 다음 전송부터 정상 체크가 재개된다.
 */
public interface ChatKickRepository {

    Mono<Boolean> isKicked(UUID roomId, UUID userId);

    /**
     * 방이 끝날 때 그 방의 강퇴 명단을 통째로 지운다.
     *
     * <p>이 키에는 TTL이 없다 — 방송 도중 만료되면 강퇴자가 조용히 되돌아오기 때문이다. 대신 끝날 때
     * 지워야 하는데, 그 삭제를 부르는 곳이 없으면 방 하나당 SET 하나가 Redis에 영구히 남는다.
     * 지금은 등록하는 쪽이 없어 실제로 쌓이지 않지만, 강퇴 기능이 붙는 순간부터 쌓이기 시작한다.
     *
     * <p>없는 키를 지우는 것도 정상 동작이라 어느 Pod가 몇 번 불러도 안전하다.
     */
    Mono<Void> clearRoom(UUID roomId);
}
