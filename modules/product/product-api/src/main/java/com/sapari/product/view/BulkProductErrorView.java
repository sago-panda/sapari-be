package com.sapari.product.view;

/**
 * 대량 등록에서 상품 한 건의 오류.
 *
 * <p>메시지는 판매자가 파일을 고칠 수 있도록 위반 내용을 구체적으로 담는다. 판매자 본인이 올린 값(옵션값·SKU·카테고리 id 등)만 포함한다.
 *
 * @param productCode 파일의 상품 코드
 * @param code        에러 코드(예: PRODUCT-004)
 * @param message     위반 내용
 */
public record BulkProductErrorView(
        String productCode,
        String code,
        String message
) {
}
