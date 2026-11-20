package com.sapari.chat.domain.exception;

import com.sapari.common.core.exception.BusinessException;

public abstract class ChatException extends BusinessException {

    public ChatException(ChatErrorCode errorCode) {
        super(errorCode);
    }

    public ChatException(ChatErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }

    public ChatException(ChatErrorCode errorCode, String customMessage) {
        super(errorCode, customMessage);
    }

    public ChatException(ChatErrorCode errorCode, String customMessage, Throwable cause) {
        super(errorCode, customMessage, cause);
    }

    @Override
    public ChatErrorCode getErrorCode() {
        return (ChatErrorCode) super.getErrorCode();
    }
}
