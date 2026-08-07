package com.sapari.live.command;

/**
 * LiveKit webhook 수신 요청. {@code body}는 서명 검증을 위해 <b>원본 바이트 그대로</b>(문자열 재인코딩 없이)
 * 전달한다 — String으로 받으면 서블릿 컨테이너의 charset 디코딩으로 바이트가 달라져 서명이 깨질 수 있다.
 * {@code authHeader}는 서명 검증용 {@code Authorization} 헤더 값(없으면 null).
 */
public record LiveWebhookCommand(
        byte[] body,
        String authHeader
) {
}
