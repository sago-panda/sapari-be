package com.sapari.common.core.exception;

/**
 * 모든 도메인 비즈니스 예외의 공통 베이스.
 *
 * 도메인 예외는 이 클래스를 상속, {@link ErrorCode}를 들고 다닌다.
 * GlobalExceptionHandler는 {@code BusinessException} 타입 하나만 잡으면
 * 모든 도메인 예외를 일관되게 처리 가능.
 */
public abstract class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    protected BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    protected BusinessException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }

    /**
     * 디버깅용 커스텀 메시지를 함께 담는 생성자.
     * 이 메시지는 예외의 {@code getMessage()}(=로그용)일 뿐이며,
     * 클라이언트 응답 메시지는 항상 {@code errorCode.getMessage()}를 쓴다.
     * (id 같은 내부 식별자가 응답으로 새어 나가지 않게 하기 위함)
     */
    protected BusinessException(ErrorCode errorCode, String debugMessage) {
        super(debugMessage);
        this.errorCode = errorCode;
    }

    protected BusinessException(ErrorCode errorCode, String debugMessage, Throwable cause) {
        super(debugMessage, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
