package com.sapari.live.domain.exception;

public class LiveNotFoundException extends LiveDomainException{

    public LiveNotFoundException(String message){
        super(LiveErrorCode.LIVE_NOT_FOUND, message);
    }
}
