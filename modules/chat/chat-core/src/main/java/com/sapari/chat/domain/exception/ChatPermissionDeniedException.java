package com.sapari.chat.domain.exception;

public class ChatPermissionDeniedException extends ChatException {

    public ChatPermissionDeniedException(String message) {
        super(ChatErrorCode.PERMISSION_DENIED, message);
    }
}
