package com.sapari.common.page;

import com.sapari.common.core.exception.BusinessException;
import com.sapari.common.core.exception.CommonErrorCode;

/**
 * 클라이언트가 보낸(신뢰 불가) 커서의 형식 오류. 항상 {@code CommonErrorCode.INVALID_INPUT}(400)로 매핑된다.
 *
 * <p>BusinessException 을 상속하므로 별도 핸들러 없이 각 런타임의 베이스 캐치(서블릿 GlobalExceptionHandler,
 * reactive ExceptionHandler)가 4xx=warn 로그 + 봉투 응답으로 일관 처리한다. servlet 의존이 없어 WebFlux 에서도 던질 수 있다.
 *
 * <p>debugMessage 는 로그 전용이며 커서 원문을 담지 않는다(어뷰저 입력이 로그/응답에 새지 않게).
 */
public final class InvalidCursorException extends BusinessException {

    public InvalidCursorException(String debugMessage) {
        super(CommonErrorCode.INVALID_INPUT, debugMessage);
    }

    public InvalidCursorException(String debugMessage, Throwable cause) {
        super(CommonErrorCode.INVALID_INPUT, debugMessage, cause);
    }
}
