package com.sapari.product.domain.exception;

/**
 * 옵션 조합을 찾을 수 없을 때 발생한다 (미존재 또는 해당 상품 소속이 아닌 조합 참조).
 */
public class CombinationNotFoundException extends ProductDomainException {

    /**
     * 찾지 못한 조합 id 등을 디버그 메시지로 담아 생성한다 (응답은 PRODUCT-008 카탈로그 메시지).
     */
    public CombinationNotFoundException(String debugMessage) {
        super(ProductErrorCode.COMBINATION_NOT_FOUND, debugMessage);
    }
}
