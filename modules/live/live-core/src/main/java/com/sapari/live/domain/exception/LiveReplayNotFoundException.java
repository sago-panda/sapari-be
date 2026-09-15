package com.sapari.live.domain.exception;

public class LiveReplayNotFoundException extends LiveDomainException {
    public LiveReplayNotFoundException(String message) {
        super(LiveErrorCode.LIVE_REPLAY_NOT_FOUND, message);
    }
}
