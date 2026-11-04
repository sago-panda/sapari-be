package com.sapari.chat.domain.exception;

public class UserKickedException extends ChatException {

    public UserKickedException(String message) {
        super(ChatErrorCode.USER_KICKED, message);
    }
}
