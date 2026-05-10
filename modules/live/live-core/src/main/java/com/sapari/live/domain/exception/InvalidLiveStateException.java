package com.sapari.live.domain.exception;

public class InvalidLiveStateException extends LiveDomainException{

    public InvalidLiveStateException(String message){
        super(LiveErrorCode.INVALID_LIVE_STATE, message);
    }
}
