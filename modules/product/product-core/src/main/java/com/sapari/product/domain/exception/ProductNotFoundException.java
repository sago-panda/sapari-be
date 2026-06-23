package com.sapari.product.domain.exception;

/**
 * 상품을 찾을 수 없을 때 발생한다 (미존재 또는 소프트 삭제된 상품 조회).
 */
public class ProductNotFoundException extends ProductDomainException {

    /**
     * 찾지 못한 productId 등을 디버그 메시지로 담아 생성한다 (응답은 PRODUCT-001 카탈로그 메시지).
     */
    public ProductNotFoundException(String debugMessage) {
        super(ProductErrorCode.PRODUCT_NOT_FOUND, debugMessage);
    }
}
