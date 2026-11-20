package com.sapari.product.domain.exception;

/**
 * 옵션 구성이 유효하지 않을 때 발생한다 (예: 값이 없는 옵션 타입, 옵션 타입명·옵션값 중복).
 */
public class InvalidProductOptionException extends ProductDomainException {

    /**
     * 위반 내용을 디버그 메시지로 담아 생성한다 (응답은 PRODUCT-004 카탈로그 메시지).
     */
    public InvalidProductOptionException(String debugMessage) {
        super(ProductErrorCode.INVALID_PRODUCT_OPTION, debugMessage);
    }
}
