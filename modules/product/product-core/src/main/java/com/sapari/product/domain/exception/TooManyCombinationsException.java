package com.sapari.product.domain.exception;

/**
 * 옵션 조합 수가 허용 한도(상품당 500개)를 초과할 때 발생한다.
 */
public class TooManyCombinationsException extends ProductDomainException {

    /**
     * 초과 수량 등 위반 내용을 디버그 메시지로 담아 생성한다 (응답은 PRODUCT-009 카탈로그 메시지).
     */
    public TooManyCombinationsException(String debugMessage) {
        super(ProductErrorCode.TOO_MANY_COMBINATIONS, debugMessage);
    }
}
