package com.sapari.product.port;

import com.sapari.product.command.UpdateProductInfoCommand;

/**
 * 상품 기본 정보 수정 인바운드 포트. 수정 시 재검수 대기로 전환된다.
 */
public interface UpdateProductInfoUseCase {

    /**
     * 상품 기본 정보를 수정한다(소유자만 가능, 수정 후 PENDING_REVIEW로 전환).
     */
    void update(UpdateProductInfoCommand command);
}
