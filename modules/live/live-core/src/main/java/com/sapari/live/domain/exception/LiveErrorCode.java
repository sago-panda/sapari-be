package com.sapari.live.domain.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import com.sapari.common.core.exception.ErrorCode;

@Getter
@RequiredArgsConstructor
public enum LiveErrorCode implements ErrorCode {

    MEDIA_SERVER_ERROR(500, "LIVE-001", "미디어 서버와 통신 중 오류가 발생했습니다."),
    LIVE_NOT_FOUND(404, "LIVE-002", "방송을 찾을 수 없습니다."),
    INVALID_LIVE_STATE(400, "LIVE-003", "방송을 시작할 수 없는 상태입니다."),
    UNSUPPORTED_ROLE(400, "LIVE-004", "입장 권한을 확인할 수 없습니다."),
    INVALID_WEBHOOK(401, "LIVE-005", "유효하지 않은 웹훅 요청입니다."),
    BROADCAST_START_FAILED(500, "LIVE-006", "방송 시작 처리 중 오류가 발생했습니다."),
    LIVE_REPLAY_NOT_FOUND(404, "LIVE-007", "다시보기를 찾을 수 없습니다."),
    INVALID_LIVE_PRODUCT_PRICE(400, "LIVE-008", "라이브 상품 가격이 올바르지 않습니다."),
    LIVE_END_NOT_ALLOWED(400, "LIVE-009", "방송을 종료할 수 없는 상태입니다."),
    LIVE_ENTER_NOT_ALLOWED(400, "LIVE-010", "방송 중인 방이 아닙니다."),
    INGRESS_NOT_ALLOWED(400, "LIVE-011", "송출 키를 발급할 수 없는 방입니다."),
    INVALID_PINNED_PRODUCT(400, "LIVE-012", "핀 상품은 정확히 1개여야 합니다."),
    CANCEL_NOT_ALLOWED(400, "LIVE-013", "취소할 수 없는 방송 상태입니다."),
    UPDATE_NOT_ALLOWED(400, "LIVE-014", "수정할 수 없는 방송 상태입니다."),
    LIVE_SCHEDULE_NOT_FUTURE(400, "LIVE-015", "방송 예정 시각은 현재 이후여야 합니다."),
    INVALID_THUMBNAIL_URL(400, "LIVE-016", "허용되지 않은 썸네일 주소입니다.");

    private final int status;
    private final String code;
    private final String message;
}
