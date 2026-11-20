package com.sapari.chat.application.port;

/**
 * 레이트리밋 판정 결과. retryAfterSeconds는 클라이언트 카운트다운용(서버는 1회만 전송).
 */
public record RateLimitResult(boolean allowed, long retryAfterSeconds) {
}
