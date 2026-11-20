package com.sapari.chat.port;

import com.sapari.chat.command.ReconcileKickListCommand;

@BlockingChat
public interface ReconcileKickListUseCase {

    /**
     * 그 방의 강퇴 집행 명단을 정본에서 다시 세운다. blocking — live-app(MVC)에서 호출.
     *
     * <p>집행 캐시({@code chat:kicked:{roomId}})가 사라지거나 우리 타입이 아닌 값으로 오염되면
     * 그 방의 강퇴가 <b>조용히, 영구히</b> 꺼진다 — 읽기 경로가 fail-open 이라 아무도 막히지 않고
     * 로그 한 줄만 남는다. 그 로그를 본 운영자가 할 수 있는 일이 이것이다.
     *
     */
    ReconcileOutcome reconcile(ReconcileKickListCommand command);

    /**
     * 재적재의 결과.
     *
     * @param restored      명단에 올린 사람 수. 0 이면 그 방에 강퇴 기록이 없다는 뜻이다(실패가 아니다)
     * @param wasCorrupted  고치기 전 키가 <b>우리 타입이 아니었는가</b>. 참이면 그 방의 강퇴 집행이
     *                      꺼져 있었다는 직접 증거다 — 조치가 필요했다는 사실 중 가장 센 신호이고,
     *                      {@code DEL} 뒤에는 알 수 없으므로 어댑터가 미리 읽어 넘긴다
     */
    record ReconcileOutcome(int restored, boolean wasCorrupted) {
    }
}
