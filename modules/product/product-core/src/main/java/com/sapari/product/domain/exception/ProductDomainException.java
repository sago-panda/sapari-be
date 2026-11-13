package com.sapari.product.domain.exception;

import com.sapari.common.core.exception.BusinessException;

/**
 * 상품 도메인 예외의 베이스. 모든 상품 도메인 예외는 이 클래스를 상속해 {@link ProductErrorCode}를 들고 다닌다. {@code GlobalExceptionHandler}는
 * {@code BusinessException} 한 타입으로 잡아 일관 처리한다.
 */
public class ProductDomainException extends BusinessException {

    /**
     * 에러 코드만으로 생성한다 (클라이언트 메시지는 코드 카탈로그 메시지).
     */
    public ProductDomainException(ProductErrorCode errorCode) {
        super(errorCode);
    }

    /**
     * 에러 코드와 로그용 디버그 메시지로 생성한다 (디버그 메시지는 응답에 노출되지 않는다).
     */
    public ProductDomainException(ProductErrorCode errorCode, String debugMessage) {
        super(errorCode, debugMessage);
    }

    /**
     * 에러 코드와 원인 예외로 생성한다.
     */
    public ProductDomainException(ProductErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }

    /**
     * 에러 코드·디버그 메시지·원인 예외로 생성한다.
     */
    public ProductDomainException(ProductErrorCode errorCode, String debugMessage, Throwable cause) {
        super(errorCode, debugMessage, cause);
    }

    /**
     * 상품 도메인 에러 코드로 좁혀 반환한다.
     */
    @Override
    public ProductErrorCode getErrorCode() {
        return (ProductErrorCode) super.getErrorCode();
    }
}
