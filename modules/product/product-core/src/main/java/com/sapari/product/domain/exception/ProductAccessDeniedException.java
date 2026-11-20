package com.sapari.product.domain.exception;

/**
 * 상품에 대한 권한이 없을 때 발생한다 (예: 소유 판매자가 아닌 사용자가 수정·삭제 시도).
 */
public class ProductAccessDeniedException extends ProductDomainException {

    /**
     * 위반 주체·상품 등을 디버그 메시지로 담아 생성한다 (응답은 PRODUCT-003 카탈로그 메시지).
     */
    public ProductAccessDeniedException(String debugMessage) {
        super(ProductErrorCode.PRODUCT_ACCESS_DENIED, debugMessage);
    }
}
