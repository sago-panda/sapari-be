package com.sapari.live.domain.exception;

/** 목록 조회 조건의 거부 — 관리자 방 목록(LIVE-020). IAE 로 두면 전역 처리기에서 500 이 된다. */
public class InvalidLiveQueryException extends LiveDomainException {

    public InvalidLiveQueryException(LiveErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
