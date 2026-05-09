package com.sapari.live.domain.exception;

public class LiveMediaException extends LiveDomainException {
    public LiveMediaException(String message, Throwable cause) {
        super(LiveErrorCode.MEDIA_SERVER_ERROR, message, cause);
    }
}