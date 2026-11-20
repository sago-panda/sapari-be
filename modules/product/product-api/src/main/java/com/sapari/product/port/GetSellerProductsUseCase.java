package com.sapari.product.port;

import com.sapari.common.page.CursorPage;
import com.sapari.product.command.GetSellerProductsCommand;
import com.sapari.product.view.ProductSummaryView;

/**
 * 판매자 상품 목록 조회 인바운드 포트. 삭제된 상품은 제외한다.
 */
public interface GetSellerProductsUseCase {

    /**
     * 판매자의 (삭제되지 않은) 상품 요약을 최신 등록순으로 한 페이지 조회한다.
     *
     * @param command 판매자 id·커서·페이지 크기
     * @return 상품 요약 페이지(다음 커서 포함)
     */
    CursorPage<ProductSummaryView> get(GetSellerProductsCommand command);
}
