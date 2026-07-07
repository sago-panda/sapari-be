package com.sapari.live.domain.exception;

/**
 * 룸 토큰 발급 시 api-app role을 chat ChatRole로 매핑할 수 없을 때. 지원하지 않는/누락된 role이 원인이며,
 * 라이브 상태 문제(INVALID_LIVE_STATE)와 구분한다. 원본 role 값은 메시지에 넣지 않는다(정보 노출 방지).
 */
public class UnsupportedRoleException extends LiveDomainException {

    public UnsupportedRoleException() {
        super(LiveErrorCode.UNSUPPORTED_ROLE);
    }
}
