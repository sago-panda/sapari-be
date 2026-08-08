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
     * 방이 끝나면 그 방의 강퇴 명단에 만료 시각을 붙인다.
     *
     * <p>평시엔 TTL이 없다 — 방송 도중 만료되면 강퇴자가 조용히 되돌아오기 때문이다. 그래서 끝날 때
     * 회수해야 하는데, 회수하는 곳이 없으면 방 하나당 SET 하나가 Redis에 영구히 남는다.
     *
     * <p><b>지우지 않고 만료시키는 이유</b>: 이 호출을 부르는 근거는 Pub/Sub으로 받은 종료 신호 한 건이고,
     * 그게 진짜인지 확인할 수단이 chat에는 없다. 즉시 지우면 잘못된 신호 하나로 그 방의 집행 상태가
     * 그 자리에서 사라지지만, 만료로 두면 유예 시간 안에 알아챌 여지가 남는다. 누수를 막는다는 목적은
     * 그대로 달성되고, 없는 키에 걸어도 무동작이라 비용도 같다.
     *
     * <p>이 키는 재접속을 빠르게 막기 위한 <b>집행 캐시</b>로 설계됐다. 정본이 될 강퇴 로그 테이블은
     * 스키마에만 있고 <b>읽고 쓰는 코드가 아직 없다</b> — 즉 지금 이 SET이 사라지면 되살릴 근거도 없다.
     * "만료라서 복구 가능"은 강퇴 기능(SADD + 로그 적재)이 붙은 뒤에야 성립한다.
     * 지금은 등록하는 쪽이 없어 실제로 쌓이지도 않는다.
     *
     * <p>없는 키에 만료를 걸어도 정상 동작이라 어느 Pod가 몇 번 불러도 안전하다.
     */
    Mono<Void> expireAfterRoomEnded(UUID roomId);
}
