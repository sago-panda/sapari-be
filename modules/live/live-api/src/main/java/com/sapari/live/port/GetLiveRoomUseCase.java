package com.sapari.live.port;

import java.util.Optional;
import java.util.UUID;

import com.sapari.live.view.LiveRoomView;

public interface GetLiveRoomUseCase {

    /**
     * 방 단건 조회(읽기 전용). 없는 방은 예외가 아니라 {@code Optional.empty()} 다.
     *
     * <p>이 포트는 소유권을 검사하지 않는다(principal 을 받지 않는다). 열거 방어는 <b>호출자 책임</b>이다 —
     * {@code empty} 와 "권한 없음"을 같은 응답으로 뭉개지 않으면 방 id 열거 통로가 열린다.
     */
    Optional<LiveRoomView> findRoom(UUID roomId);
}
