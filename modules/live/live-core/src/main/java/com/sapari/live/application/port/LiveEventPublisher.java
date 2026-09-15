package com.sapari.live.application.port;

import java.time.Instant;
import java.util.UUID;

/**
 * live 도메인 이벤트를 다른 앱(chat 등)으로 전파하는 아웃바운드 포트.
 *
 * <p>현재는 방 종료(RoomEnded)만 발행한다. chat(streaming-app)이 구독해 해당 방의 WS 세션을 닫는다.
 * 전달은 best-effort(at-most-once) — 유실 시 chat 측 송신 가드/재조정으로 백스톱한다.
 */
public interface LiveEventPublisher {

    /**
     * 방 종료를 발행한다. 트랜잭션 커밋 이후 호출해 롤백 시 오발행(멀쩡한 방 세션 종료)을 막는다.
     */
    void publishRoomEnded(UUID roomId, Instant endedAt);
}
