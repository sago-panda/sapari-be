package com.sapari.product.port;

import com.sapari.product.command.ChangeSaleStatusCommand;

/**
 * 판매 상태 전환 인바운드 포트. ON_SALE ↔ SUSPENDED.
 */
public interface ChangeProductSaleStatusUseCase {

    /**
     * 상품 판매 상태를 전환한다(소유자만 가능).
     */
    void change(ChangeSaleStatusCommand command);
}
