package com.sapari.product.command;

/**
 * 대량 등록의 상품 한 건. 업로드 파일 안에서 상품을 가리키는 코드와 등록 커맨드를 묶는다.
 *
 * @param productCode 파일 안에서만 유일한 상품 코드(판매자 지정, 저장되지 않음). 결과·오류를 파일 행에 되돌려 매핑하는 키
 * @param product     상품 등록 커맨드
 */
public record BulkProductItemCommand(
        String productCode,
        CreateProductCommand product
) {
}
