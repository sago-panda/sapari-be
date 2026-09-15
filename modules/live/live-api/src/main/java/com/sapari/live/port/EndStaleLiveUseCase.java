package com.sapari.live.port;

import com.sapari.live.command.EndStaleLiveCommand;

/**
 * 방치된 Live 방 종료 — 방송은 끝났는데 {@code room_finished} 유실 등으로 Live 에 갇힌 방을 닫는다.
 * 호출자가 배치뿐이라(판매자 주체 없는 시스템 정리) 소유권 검사가 없다.
 *
 * <p><b>종료 대상 판정은 여기서 하지 않는다.</b> "방송이 실제로 끝났는가"는 LiveKit 을 봐야 알 수 있어
 * {@link ReconcileStaleLiveUseCase} 가 판정하고, 이 유스케이스는 방 1건의 전이·정리만 책임진다.
 */
public interface EndStaleLiveUseCase {
    void endStale(EndStaleLiveCommand command);
}
