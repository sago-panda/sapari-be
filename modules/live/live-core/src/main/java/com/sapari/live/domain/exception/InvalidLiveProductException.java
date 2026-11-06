package com.sapari.live.domain.exception;

/**
 * 판매자가 보낸 라이브 상품 입력의 거부 — 가격 규칙(LIVE-008), 핀 상품 개수(LIVE-012).
 *
 * <p>{@code IllegalArgumentException} 으로 던지면 전역 핸들러의 최후 그물이 500 으로 받는다.
 * 클라이언트 입력이므로 도메인 예외로 던져 400 이 되게 한다. 서버가 채우는 값의 불변식
 * (liveRoomId·pinnedAt 등)은 여기 오지 않고 IAE 로 남는다 — 그쪽은 서버 결함이다.
 */
public class InvalidLiveProductException extends LiveDomainException {

    public InvalidLiveProductException(LiveErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
