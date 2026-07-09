package com.sapari.productapp.controller.product;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sapari.product.command.GetProductDetailCommand;
import com.sapari.product.domain.exception.ProductNotFoundException;
import com.sapari.product.domain.model.product.ProductStatus;
import com.sapari.product.port.GetProductDetailUseCase;
import com.sapari.product.view.ProductDetailView;

import lombok.RequiredArgsConstructor;

/**
 * 구매자용 상품 공개 조회 컨트롤러. 인증이 필요 없으며 판매 가능한 상품만 노출한다.
 *
 * <p>비공개 상태(PENDING_REVIEW/SUSPENDED/REJECTED) 상품은 존재 자체를 숨겨야 하므로(M-4),
 * 단일 상세 유스케이스를 재사용하되 컨트롤러 경계에서 {@code ON_SALE} 상태 게이트를 적용해
 * 그 외 상태는 404로 응답한다(판매자 전용 상세 엔드포인트가 생기면 게이트 위치 재검토).
 */
@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductController {

    private final GetProductDetailUseCase getProductDetailUseCase;

    /**
     * 판매 중인 상품의 상세를 조회한다. 삭제·비공개 상태는 404로 취급한다.
     *
     * @param productId 조회할 상품 id
     * @return 200 OK + 상품 상세
     */
    @GetMapping("/{productId}")
    public ResponseEntity<ProductDetailView> getDetail(
            @PathVariable UUID productId
    ) {
        ProductDetailView view = getProductDetailUseCase.get(new GetProductDetailCommand(productId));
        if (!ProductStatus.ON_SALE.name().equals(view.status())) {
            throw new ProductNotFoundException("상품을 찾을 수 없습니다: " + productId);
        }
        return ResponseEntity.ok(view);
    }
}
