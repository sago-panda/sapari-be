package com.sapari.live.domain.exception;

/**
 * 관리자가 라이브 권한을 끈 판매자의 요청(LIVE-019). 방 상태가 아니라 판매자 권한 문제라
 * {@link InvalidLiveStateException} 으로 두지 않는다 — 재조정 배치가 그 타입을 "경합에서 짐"으로 읽는다.
 */
public class LivePermissionDeniedException extends LiveDomainException {

    public LivePermissionDeniedException() {
        super(LiveErrorCode.LIVE_PERMISSION_DENIED);
    }
}
