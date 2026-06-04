package com.sapari.common.core.exception;

/**
 * 모든 도메인 에러 코드가 구현하는 공통 에러 코드
 *
 * 각 도메인의 {@code XErrorCode} enum이 이 인터페이스를 implements
 * GlobalExceptionHandler가 도메인을 몰라도 일관된 응답을 만들 수 있다.
 */
public interface ErrorCode {

    int getStatus();

    String getCode();

    String getMessage();
}
