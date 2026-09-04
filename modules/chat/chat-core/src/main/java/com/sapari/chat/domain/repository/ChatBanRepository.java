package com.sapari.chat.domain.repository;

import java.util.UUID;

import reactor.core.publisher.Mono;

/**
 * 플랫폼 밴 여부 조회 포트 — 입장 게이트가 쓴다. 리액티브(streaming-app).
 *
 * <p><b>강퇴와 축이 다르다.</b> 강퇴는 방 하나에서 나가는 것이고 밴은 플랫폼 전체에서 막히는 것이라,
 * 이 조회에는 roomId가 없다. 어느 방에 들어오려 하든 답이 같다.
 *
 * <p><b>Redis가 정답이고 DB를 보지 않는다.</b> 밴 상태는 {@code chat_ban} 테이블이 정본이지만 입장
 * 경로에서는 그 미러(키 하나)만 읽는다 — 접속마다 관계형 조회를 태우면 재접속이 몰리는 순간 그게
 * 그대로 DB 부하가 되고, streaming-app은 애초에 관계형 DB를 갖고 있지 않다. 미러를 채우는 것은
 * 밴을 만드는 쪽(강퇴 에스컬레이션·수동 밴)의 책임이다.
 *
 * <p>해제도 키의 몫이다 — 기간 밴은 TTL이 지나면 사라지고, 영구 밴은 TTL이 없다. 상태 필드를 따로
 * 두지 않으므로 "만료됐는데 아직 밴으로 보이는" 창이 없다.
 *
 * <p><b>어댑터 계약</b>: Redis 장애 시 {@code false}로 흡수하지 말고 <b>error를 전파</b>한다.
 * {@code false}(=정상 유저)로 삼키면 소비처가 "확실히 정상"과 "조회 불가"를 구분할 수 없다.
 * 소비처(입장 게이트) 정책은 종료 마커·강퇴 조회와 같은 <b>fail-open</b>이다.
 */
public interface ChatBanRepository {

    Mono<Boolean> isBanned(UUID userId);
}
