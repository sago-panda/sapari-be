package com.sapari.chat.domain.exception;

public class ChatMessageNotFoundException extends ChatException {

    public ChatMessageNotFoundException(String debugMessage) {
        super(ChatErrorCode.MESSAGE_NOT_FOUND, debugMessage);
    }
}
