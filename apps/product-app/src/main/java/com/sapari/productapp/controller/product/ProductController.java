package com.sapari.productapp.controller.product;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sapari.common.response.ResponseEnvelope;
import com.sapari.product.command.GetProductDetailCommand;
import com.sapari.product.port.GetProductDetailUseCase;
import com.sapari.product.view.ProductDetailView;

import lombok.RequiredArgsConstructor;

/**
 * 구매자용 상품 공개 조회 컨트롤러. 인증이 필요 없으며 판매 가능한 상품만 노출한다.
 *
 * <p>비공개 상태(PENDING_REVIEW/SUSPENDED/REJECTED) 상품을 숨기는 규칙(M-4)은 {@link GetProductDetailUseCase}가 강제한다 —
 * 웹 어댑터에 두면 같은 유스케이스를 쓰는 다음 소비자가 비공개 상품을 그대로 노출하게 된다.
 */
@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductController {

    private final GetProductDetailUseCase getProductDetailUseCase;

    /**
     * 판매 중인 상품의 상세를 조회한다. 삭제·비공개 상태는 유스케이스가 404(PRODUCT-001)로 거절한다.
     *
     * @param productId 조회할 상품 id
     * @return 200 OK + 상품 상세 성공 봉투
     */
    @GetMapping("/{productId}")
    public ResponseEnvelope<ProductDetailView> getDetail(
            @PathVariable UUID productId
    ) {
        return ResponseEnvelope.success(getProductDetailUseCase.get(new GetProductDetailCommand(productId)));
    }
}
