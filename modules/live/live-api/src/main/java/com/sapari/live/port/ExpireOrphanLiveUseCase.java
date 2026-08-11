package com.sapari.live.port;

import com.sapari.live.command.ExpireOrphanLiveCommand;

/**
 * 고아 라이브 방 만료 유스케이스 - OBS가 끝내 연결되지 않아 READY 상태에 갇힌 방을 정리한다.
 * 호출자가 배치뿐이라(판매자 주체 없는 시스템 정리) 소유권 검사가 없다.
 */
public interface ExpireOrphanLiveUseCase {
    void expire(ExpireOrphanLiveCommand command);
}
