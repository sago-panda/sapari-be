package com.sapari.product.port;

import com.sapari.product.command.CreateProductCommand;
import com.sapari.product.view.CreateProductView;

/**
 * 상품 등록 인바운드 포트. 컨트롤러 → 이 포트 → {@code CreateProductService}로 위임된다.
 */
public interface CreateProductUseCase {

    /**
     * 상품을 등록한다(검수 대기 상태로 시작, 옵션 조합 자동 생성).
     */
    CreateProductView create(CreateProductCommand command);
}
