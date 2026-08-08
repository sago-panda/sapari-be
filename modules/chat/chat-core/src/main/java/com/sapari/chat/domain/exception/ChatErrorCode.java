package com.sapari.chat.domain.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import com.sapari.common.core.exception.ErrorCode;

@Getter
@RequiredArgsConstructor
public enum ChatErrorCode implements ErrorCode {

    PERMISSION_DENIED(403, "CHAT-001", "채팅 권한이 없습니다."),
    RATE_LIMIT_EXCEEDED(429, "CHAT-002", "메시지를 너무 빠르게 보내고 있습니다."),
    USER_KICKED(403, "CHAT-003", "강퇴되어 채팅에 참여할 수 없습니다."),
    LIVE_NOT_ACTIVE(400, "CHAT-004", "진행 중인 라이브가 아닙니다."),
    // 사용자 잘못이 아니라 서버 쪽 상태의 문제라 5xx다. 어느 키가 문제인지는 응답이 아니라 로그로 간다
    // (BusinessException의 debugMessage가 로그용, 응답은 이 문구 — 내부 식별자 누출 방지).
    KICK_STORE_CORRUPTED(500, "CHAT-005", "강퇴 처리를 완료할 수 없습니다.");

    private final int status;
    private final String code;
    private final String message;
}
