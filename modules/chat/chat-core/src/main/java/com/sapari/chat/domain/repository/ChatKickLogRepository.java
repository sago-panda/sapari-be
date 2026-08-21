package com.sapari.chat.domain.repository;

import com.sapari.chat.domain.model.ChatKickLog;

/**
 * 강퇴 증거 로그(Postgres) 포트 — 강퇴 흐름의 <b>정본</b>이다.
 *
 * <p>Redis 강퇴 명단은 재접속을 빠르게 막기 위한 집행 캐시고, 사라져도 여기서 되살릴 수 있다.
 * 반대로 여기에 못 남기면 그 강퇴는 일어나지 않은 것으로 친다 — 그래서 이 쓰기가 실패하면
 * 뒤따르는 Redis·발행 단계를 <b>실행하지 않는다</b>.
 */
public interface ChatKickLogRepository {

    /**
     * 강퇴를 기록한다. 같은 방·같은 유저가 이미 있으면 아무것도 하지 않는다.
     *
     * <p><b>반환값이 밴 에스컬레이션의 트리거다.</b> 누적 강퇴 카운트는 실제로 새 행이 생겼을 때만 올라야
     * 하는데, 재시도·중복 요청과 진짜 강퇴를 가르는 신호가 이것뿐이다. 이걸 무시하고 매번 카운트를 세면
     * 재시도 한 번이 그 유저를 임계로 밀어 올린다.
     *
     * <p><b>호출자가 읽기·쓰기 트랜잭션을 열어야 한다.</b> 이 포트는 트랜잭션 경계를 만들지 않는다 —
     * 경계는 유스케이스의 책임이고, 강퇴 흐름은 이 커밋이 확정된 <i>다음</i>에 Redis와 발행으로 넘어가야
     * 하므로 경계가 어디서 닫히는지가 설계의 일부다. 전체를 하나로 묶으면 롤백 시 Redis만 남는다.
     *
     * @return 실제로 기록됐으면 {@code true}, 이미 있어 아무것도 하지 않았으면 {@code false}
     */
    boolean appendIfAbsent(ChatKickLog log);
}
