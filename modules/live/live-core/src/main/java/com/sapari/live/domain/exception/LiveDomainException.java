package com.sapari.live.domain.exception;

import lombok.Getter;

@Getter
public class LiveDomainException extends RuntimeException{
    private final LiveErrorCode errorCode;

    public LiveDomainException(LiveErrorCode errorCode){
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public LiveDomainException(LiveErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }

    public LiveDomainException(LiveErrorCode errorCode, String customMessage, Throwable cause) {
        super(customMessage, cause);
        this.errorCode = errorCode;
    }

    public LiveDomainException(LiveErrorCode errorCode, String customMessage){
        super(customMessage);
        this.errorCode = errorCode;
    }
}
