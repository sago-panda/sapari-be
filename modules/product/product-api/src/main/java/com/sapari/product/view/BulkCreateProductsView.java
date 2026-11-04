package com.sapari.product.view;

import java.util.List;

/**
 * 상품 대량 등록 결과.
 *
 * <ul>
 *   <li>{@link #REJECTED} — 사전 검증에서 오류가 나 아무것도 저장하지 않았다. {@code errors}에 모든 오류가 담긴다.
 *   <li>{@link #COMPLETED} — 모든 상품을 저장했다.
 *   <li>{@link #STOPPED} — 검증은 통과했지만 저장 도중 한 상품이 실패해 중단했다(예: 그 사이 카테고리 삭제).
 *       {@code created}는 이미 저장된 상품, {@code errors}는 실패한 상품 1건이며 나머지는 시도하지 않았다.
 * </ul>
 *
 * @param status  처리 결과({@link #REJECTED}·{@link #COMPLETED}·{@link #STOPPED})
 * @param created 저장된 상품들(파일 순서)
 * @param errors  오류들
 */
public record BulkCreateProductsView(
        String status,
        List<BulkCreatedProductView> created,
        List<BulkProductErrorView> errors
) {

    public static final String REJECTED = "REJECTED";
    public static final String COMPLETED = "COMPLETED";
    public static final String STOPPED = "STOPPED";
}
