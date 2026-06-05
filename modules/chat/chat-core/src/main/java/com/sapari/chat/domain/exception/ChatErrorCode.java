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
    LIVE_NOT_ACTIVE(400, "CHAT-004", "진행 중인 라이브가 아닙니다."); 

    private final int status;
    private final String code;
    private final String message;
}
