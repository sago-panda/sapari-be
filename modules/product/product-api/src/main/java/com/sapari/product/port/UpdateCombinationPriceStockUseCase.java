package com.sapari.product.port;

import com.sapari.product.command.UpdateCombinationPriceStockCommand;

/**
 * 조합 가격·재고 수정 인바운드 포트. 즉시 반영되며 재승인이 필요 없다.
 */
public interface UpdateCombinationPriceStockUseCase {

    /**
     * 조합들의 가격·재고·판매여부를 변경한다(소유자만 가능).
     */
    void update(UpdateCombinationPriceStockCommand command);
}
