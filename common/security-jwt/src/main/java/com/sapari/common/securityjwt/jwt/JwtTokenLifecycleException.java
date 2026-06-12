package com.sapari.common.securityjwt.jwt;

/**
 * JWT 토큰 생명주기 처리 중 발생한 검증 실패를 나타낸다.
 * 도메인별 응답 코드는 seller/customer 래퍼에서 각 도메인 예외로 변환한다.
 */
public class JwtTokenLifecycleException extends RuntimeException {

    public JwtTokenLifecycleException(String message) {
        super(message);
    }

    public JwtTokenLifecycleException(String message, Throwable cause) {
        super(message, cause);
    }
}
