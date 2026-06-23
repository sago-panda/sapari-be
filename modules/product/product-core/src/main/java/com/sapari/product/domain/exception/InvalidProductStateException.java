package com.sapari.product.domain.exception;

/**
 * 허용되지 않는 상품 상태 전이를 시도할 때 발생한다 (예: ON_SALE이 아닌 상품 중지, 소프트 삭제된 상품 수정).
 */
public class InvalidProductStateException extends ProductDomainException {

    /**
     * 위반한 전이 내용을 디버그 메시지로 담아 생성한다 (응답은 PRODUCT-002 카탈로그 메시지).
     */
    public InvalidProductStateException(String debugMessage) {
        super(ProductErrorCode.INVALID_PRODUCT_STATE, debugMessage);
    }
}
