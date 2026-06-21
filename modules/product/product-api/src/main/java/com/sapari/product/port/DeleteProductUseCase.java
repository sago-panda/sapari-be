package com.sapari.product.port;

import com.sapari.product.command.DeleteProductCommand;

/**
 * 상품 삭제 인바운드 포트. 논리 삭제(deletedAt 기록).
 */
public interface DeleteProductUseCase {

    /**
     * 상품을 논리 삭제한다(소유자만 가능, 되돌리기 불가).
     */
    void delete(DeleteProductCommand command);
}
