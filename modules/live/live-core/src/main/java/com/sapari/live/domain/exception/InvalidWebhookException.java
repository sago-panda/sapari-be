package com.sapari.live.domain.exception;

/**
 * LiveKit webhook 서명 검증 실패(위변조·서명 불일치·파싱 불가). 원본 요청 값은 메시지에 담지 않는다.
 */
public class InvalidWebhookException extends LiveDomainException {

    public InvalidWebhookException() {
        super(LiveErrorCode.INVALID_WEBHOOK);
    }

    public InvalidWebhookException(Throwable cause) {
        super(LiveErrorCode.INVALID_WEBHOOK, cause);
    }
}
