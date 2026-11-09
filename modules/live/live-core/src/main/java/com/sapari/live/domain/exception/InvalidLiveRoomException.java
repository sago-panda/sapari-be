package com.sapari.live.domain.exception;

/**
 * 판매자가 보낸 방 입력의 거부 — 예정 시각(LIVE-015), 썸네일 주소(LIVE-016).
 *
 * <p>{@link InvalidLiveProductException} 과 같은 이유로 IAE 가 아니라 도메인 예외다 — 클라이언트 입력이
 * 500 으로 새지 않게 한다.
 */
public class InvalidLiveRoomException extends LiveDomainException {

    public InvalidLiveRoomException(LiveErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
