package com.sapari.live.domain.exception;

import com.sapari.common.core.exception.BusinessException;

public class LiveDomainException extends BusinessException {

    public LiveDomainException(LiveErrorCode errorCode) {
        super(errorCode);
    }

    public LiveDomainException(LiveErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }

    public LiveDomainException(LiveErrorCode errorCode, String customMessage, Throwable cause) {
        super(errorCode, customMessage, cause);
    }

    public LiveDomainException(LiveErrorCode errorCode, String customMessage) {
        super(errorCode, customMessage);
    }

    @Override
    public LiveErrorCode getErrorCode() {
        return (LiveErrorCode) super.getErrorCode();
    }
}
