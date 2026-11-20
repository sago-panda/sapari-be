package com.sapari.product.port;

import com.sapari.product.command.GetProductDetailCommand;
import com.sapari.product.view.ProductDetailView;

/**
 * 구매자용 상품 공개 상세 조회 인바운드 포트. 상품 + 옵션 트리 + 조합을 조립해 돌려준다.
 *
 * <p>판매 중({@code ON_SALE})인 상품만 노출한다. 비공개 상태 상품은 존재 자체를 숨긴다 — 이 규칙은 어느 소비자가 호출하든
 * 지켜져야 하므로 웹 어댑터가 아니라 유스케이스가 강제한다. 판매자 본인 상세가 필요해지면 소유권 검증을 갖춘 별도 유스케이스로 둔다.
 */
public interface GetProductDetailUseCase {

    /**
     * 판매 중인 상품의 상세를 조회한다. 상품이 없거나, 삭제됐거나, 판매 중이 아니면 모두 같은 상품 미존재 예외(PRODUCT-001)로 거절한다.
     *
     * @param command 조회할 상품 id
     * @return 상품 상세
     */
    ProductDetailView get(GetProductDetailCommand command);
}
