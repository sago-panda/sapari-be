package com.sapari.product.domain.exception;

/**
 * 존재하지 않는 카테고리를 참조할 때 발생한다 (예: 상품 등록·수정 시 잘못된 categoryId).
 */
public class CategoryNotFoundException extends ProductDomainException {

    /**
     * 찾지 못한 categoryId 등을 디버그 메시지로 담아 생성한다 (응답은 PRODUCT-005 카탈로그 메시지).
     */
    public CategoryNotFoundException(String debugMessage) {
        super(ProductErrorCode.CATEGORY_NOT_FOUND, debugMessage);
    }
}
