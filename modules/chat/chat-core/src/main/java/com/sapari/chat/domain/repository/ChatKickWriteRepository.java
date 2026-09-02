package com.sapari.chat.domain.repository;

import java.util.UUID;

/**
 * 강퇴 명단에 사람을 올리는 포트. <b>블로킹</b> — 호출자가 api-app(MVC)이다.
 *
 * <p>읽는 쪽은 {@link ChatKickRepository}(리액티브, streaming-app)다. 같은 Redis SET을 두 스택이 나눠
 * 쓰는 셈이라 키 문자열은 {@code infrastructure.redis} 패키지의 단일 소스가 계속 쥐고 있다 —
 * 읽는 쪽과 쓰는 쪽이 다른 키를 보게 되는 순간 강퇴는 조용히 통과한다.
 *
 * <p>이 SET은 <b>집행 캐시</b>다. 정본은 {@link ChatKickLogRepository}(Postgres)이고, 그래서 순서가
 * 정해져 있다 — 로그가 커밋으로 확정된 <i>다음에</i> 여기 올린다. 반대로 하면 롤백된 강퇴가 Redis에만
 * 남아 근거 없이 사람을 막는다.
 */
public interface ChatKickWriteRepository {

    /**
     * 이 방의 강퇴 명단에 올린다. 이미 있어도 그대로 성공이다(집합이라 두 번 넣어도 하나다).
     *
     * <p><b>만료를 함께 걷어낸다.</b> 방이 끝날 때 이 키에는 회수용 만료가 붙는데, 그 신호가 잘못 온
     * 것이었다면 방송은 계속되고 키에는 만료만 남아 있다. 그 상태에서 새로 강퇴를 올리면 — Redis의
     * 집합 추가는 남아 있는 만료를 건드리지 않으므로 — 명단 전체가 만료 시각에 조용히 사라지고
     * 강퇴됐던 사람이 전원 돌아온다. 그래서 올릴 때마다 만료를 떼어 낸다.
     *
     * <p>실패는 삼키지 않고 전파한다. 여기서 실패하면 그 사람은 재접속으로 곧장 돌아오므로,
     * 호출자가 강퇴를 실패로 처리하고 다시 시도할 수 있어야 한다.
     */
    void register(UUID roomId, UUID userId);
}
