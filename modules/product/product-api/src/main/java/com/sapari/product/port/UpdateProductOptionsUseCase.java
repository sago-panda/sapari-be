package com.sapari.product.port;

import com.sapari.product.command.UpdateProductOptionsCommand;

/**
 * 상품 옵션 수정 인바운드 포트. 옵션 타입/값 교체 + 조합 재생성.
 */
public interface UpdateProductOptionsUseCase {

    /**
     * 상품 옵션을 교체하고 조합을 재생성한다(소유자만 가능).
     */
    void update(UpdateProductOptionsCommand command);
}
