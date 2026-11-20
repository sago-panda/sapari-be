package com.sapari.chat.domain.exception;

public class LiveNotActiveException extends ChatException {

    public LiveNotActiveException(String message) {
        super(ChatErrorCode.LIVE_NOT_ACTIVE, message);
    }
}
