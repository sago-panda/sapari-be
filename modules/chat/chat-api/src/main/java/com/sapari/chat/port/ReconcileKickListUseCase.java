package com.sapari.chat.port;

import com.sapari.chat.command.ReconcileKickListCommand;

public interface ReconcileKickListUseCase {

    /**
     * 그 방의 강퇴 집행 명단을 정본에서 다시 세운다. blocking — live-app(MVC)에서 호출.
     *
     * <p>집행 캐시({@code chat:kicked:{roomId}})가 사라지거나 우리 타입이 아닌 값으로 오염되면
     * 그 방의 강퇴가 <b>조용히, 영구히</b> 꺼진다 — 읽기 경로가 fail-open 이라 아무도 막히지 않고
     * 로그 한 줄만 남는다. 그 로그를 본 운영자가 할 수 있는 일이 이것이다.
     *
     * @return 명단에 올린 사람 수 — 0 이면 그 방에 강퇴 기록이 없다는 뜻이다(실패가 아니다)
     */
    int reconcile(ReconcileKickListCommand command);
}
