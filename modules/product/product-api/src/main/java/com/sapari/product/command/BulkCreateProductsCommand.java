package com.sapari.product.command;

import java.util.List;

/**
 * 상품 대량 등록 입력. 파일 하나에서 읽어낸 상품들을 담는다.
 *
 * @param items 등록할 상품들(상품 코드는 요청 안에서 유일, 최대 100개)
 */
public record BulkCreateProductsCommand(
        List<BulkProductItemCommand> items
) {

    /**
     * 한 번에 등록할 수 있는 상품 수 상한 (동기 처리 기준 — 최악 100 × 조합 500).
     */
    public static final int MAX_ITEMS = 100;
}
