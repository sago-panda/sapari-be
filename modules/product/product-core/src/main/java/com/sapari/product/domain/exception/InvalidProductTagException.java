package com.sapari.product.domain.exception;

/**
 * 상품 태그 규칙(최대 10개, 각 20자 이내, 특수문자 불가)을 위반할 때 발생한다.
 */
public class InvalidProductTagException extends ProductDomainException {

    /**
     * 위반한 태그 내용을 디버그 메시지로 담아 생성한다 (응답은 PRODUCT-007 카탈로그 메시지).
     */
    public InvalidProductTagException(String debugMessage) {
        super(ProductErrorCode.INVALID_PRODUCT_TAG, debugMessage);
    }
}
