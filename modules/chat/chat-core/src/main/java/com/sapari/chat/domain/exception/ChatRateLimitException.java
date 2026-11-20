package com.sapari.chat.domain.exception;

import lombok.Getter;

@Getter
public class ChatRateLimitException extends ChatException {

    // 클라 카운트다운용 잔여 TTL — RateLimiter에서만 계산되는 값이라 예외에 실어 transport(RATE_LIMIT 응답)까지 운반한다.
    private final long retryAfterSeconds;

    public ChatRateLimitException(String message, long retryAfterSeconds) {
        super(ChatErrorCode.RATE_LIMIT_EXCEEDED, message);
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
