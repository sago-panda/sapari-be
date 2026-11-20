package com.sapari.product.port;

import com.sapari.product.command.BulkCreateProductsCommand;
import com.sapari.product.view.BulkCreateProductsView;

/**
 * 상품 대량 등록 유스케이스. 모든 상품을 먼저 검증해 하나라도 오류면 아무것도 저장하지 않고, 통과하면 상품마다 트랜잭션을 나눠 저장한다.
 */
public interface BulkCreateProductsUseCase {

    /**
     * 상품들을 일괄 등록한다.
     *
     * @param command 등록할 상품들
     * @return 처리 결과(거절·완료·중단)와 저장된 상품·오류
     */
    BulkCreateProductsView create(BulkCreateProductsCommand command);
}
