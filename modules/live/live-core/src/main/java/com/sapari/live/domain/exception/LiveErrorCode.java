package com.sapari.live.domain.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum LiveErrorCode {

    MEDIA_SERVER_ERROR(500, "LIVE-001", "미디어 서버와 통신 중 오류가 발생했습니다."),
    LIVE_NOT_FOUND(404, "LIVE-002", "방송을 찾을 수 없습니다."),
    INVALID_LIVE_STATE(400, "LIVE-003", "방송을 시작할 수 없는 상태입니다.");

    private final int status;
    private final String code;
    private final String message;
}
