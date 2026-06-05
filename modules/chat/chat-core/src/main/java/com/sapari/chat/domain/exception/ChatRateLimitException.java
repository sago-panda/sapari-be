package com.sapari.chat.domain.exception;

public class ChatRateLimitException extends ChatException {

    public ChatRateLimitException(String message) {
        super(ChatErrorCode.RATE_LIMIT_EXCEEDED, message);
    }
}
