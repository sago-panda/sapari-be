package com.sapari.product.port;

import com.sapari.product.command.GetProductDetailCommand;
import com.sapari.product.view.ProductDetailView;

/**
 * 상품 상세 조회 인바운드 포트. 상품 + 옵션 트리 + 조합을 조립해 돌려준다.
 */
public interface GetProductDetailUseCase {

    /**
     * 상품 상세를 조회한다. 존재하지 않거나 삭제된 상품이면 예외를 던진다.
     */
    ProductDetailView get(GetProductDetailCommand command);
}
