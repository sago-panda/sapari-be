package com.sapari.chat.port;

import com.sapari.chat.command.ClearRoomEndedMarkerCommand;

@BlockingChat
public interface ClearRoomEndedMarkerUseCase {

    /**
     * 잘못 기록된 종료 마커를 해제한다. blocking — live-app(MVC)에서 호출.
     *
     * <p><b>그 방이 live 정본에서 실제로 진행 중일 때만</b> 해제한다 — 진짜로 끝난 방은 거부된다.
     * 그 검증이 이 엔드포인트의 존재 이유이자 안전장치다: chat 은 받은 종료 신호의 진위를 확인할
     * 수단이 없지만 live 의 정본을 읽는 앱은 있다.
     */
    void clear(ClearRoomEndedMarkerCommand command);
}
