package com.sapari.product.port;

import com.sapari.product.command.GetSellerProductsCommand;
import com.sapari.product.view.ProductSummaryView;
import java.util.List;

/**
 * 판매자 상품 목록 조회 인바운드 포트. 삭제된 상품은 제외한다.
 */
public interface GetSellerProductsUseCase {

    /**
     * 판매자의 (삭제되지 않은) 상품 요약 목록을 조회한다.
     */
    List<ProductSummaryView> get(GetSellerProductsCommand command);
}
