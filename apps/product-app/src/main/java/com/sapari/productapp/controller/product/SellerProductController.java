package com.sapari.productapp.controller.product;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sapari.common.web.security.CurrentUserId;
import com.sapari.product.command.DeleteProductCommand;
import com.sapari.product.command.GetSellerProductsCommand;
import com.sapari.product.port.ChangeProductSaleStatusUseCase;
import com.sapari.product.port.CreateProductUseCase;
import com.sapari.product.port.DeleteProductUseCase;
import com.sapari.product.port.GetSellerProductsUseCase;
import com.sapari.product.port.UpdateCombinationPriceStockUseCase;
import com.sapari.product.port.UpdateProductInfoUseCase;
import com.sapari.product.port.UpdateProductOptionsUseCase;
import com.sapari.product.view.CreateProductView;
import com.sapari.product.view.ProductSummaryView;
import com.sapari.productapp.controller.product.dto.request.ChangeSaleStatusRequest;
import com.sapari.productapp.controller.product.dto.request.CreateProductRequest;
import com.sapari.productapp.controller.product.dto.request.UpdateCombinationPriceStockRequest;
import com.sapari.productapp.controller.product.dto.request.UpdateProductInfoRequest;
import com.sapari.productapp.controller.product.dto.request.UpdateProductOptionsRequest;

import lombok.RequiredArgsConstructor;

/**
 * 판매자 상품 관리 REST 컨트롤러. 모든 엔드포인트는 {@code ROLE_SELLER} 인증을 요구하며(시큐리티 체인),
 * 판매자 식별자는 본문이 아니라 {@code @CurrentUserId}로 주입해 IDOR·매스 어사인먼트를 차단한다.
 *
 * <p>소유권 검증(요청 판매자 == 상품 소유자)은 각 유스케이스가 담당한다(권한 없음 → PRODUCT-003).
 */
@RestController
@RequestMapping("/api/v1/seller/products")
@RequiredArgsConstructor
@Validated
public class SellerProductController {

    private final CreateProductUseCase createProductUseCase;
    private final GetSellerProductsUseCase getSellerProductsUseCase;
    private final UpdateProductInfoUseCase updateProductInfoUseCase;
    private final UpdateProductOptionsUseCase updateProductOptionsUseCase;
    private final UpdateCombinationPriceStockUseCase updateCombinationPriceStockUseCase;
    private final ChangeProductSaleStatusUseCase changeProductSaleStatusUseCase;
    private final DeleteProductUseCase deleteProductUseCase;

    /**
     * 상품을 등록한다. 등록 직후 상태는 검수 대기(PENDING_REVIEW)다.
     *
     * @param sellerId 인증된 판매자 id
     * @param request  상품 등록 요청
     * @return 201 Created + 생성된 상품 id·상태
     */
    @PostMapping
    public ResponseEntity<CreateProductView> create(
            @CurrentUserId UUID sellerId,
            @Valid @RequestBody CreateProductRequest request
    ) {
        CreateProductView view = createProductUseCase.create(request.toCommand(sellerId));
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    /**
     * 인증된 판매자가 등록한 상품 목록을 조회한다(모든 상태 포함).
     *
     * @param sellerId 인증된 판매자 id
     * @return 200 OK + 상품 요약 목록
     */
    @GetMapping
    public ResponseEntity<List<ProductSummaryView>> getMyProducts(
            @CurrentUserId UUID sellerId
    ) {
        List<ProductSummaryView> views = getSellerProductsUseCase.get(new GetSellerProductsCommand(sellerId));
        return ResponseEntity.ok(views);
    }

    /**
     * 상품 기본 정보를 수정한다(재승인 대상).
     *
     * @param sellerId  인증된 판매자 id
     * @param productId 수정할 상품 id
     * @param request   기본 정보 수정 요청
     * @return 204 No Content
     */
    @PutMapping("/{productId}")
    public ResponseEntity<Void> updateInfo(
            @CurrentUserId UUID sellerId,
            @PathVariable UUID productId,
            @Valid @RequestBody UpdateProductInfoRequest request
    ) {
        updateProductInfoUseCase.update(request.toCommand(sellerId, productId));
        return ResponseEntity.noContent().build();
    }

    /**
     * 상품 옵션을 통째로 교체하고 조합을 재생성한다.
     *
     * @param sellerId  인증된 판매자 id
     * @param productId 수정할 상품 id
     * @param request   옵션 수정 요청
     * @return 204 No Content
     */
    @PutMapping("/{productId}/options")
    public ResponseEntity<Void> updateOptions(
            @CurrentUserId UUID sellerId,
            @PathVariable UUID productId,
            @Valid @RequestBody UpdateProductOptionsRequest request
    ) {
        updateProductOptionsUseCase.update(request.toCommand(sellerId, productId));
        return ResponseEntity.noContent().build();
    }

    /**
     * 조합별 가격·재고를 일괄 수정한다(즉시 반영, 재승인 불필요).
     *
     * @param sellerId  인증된 판매자 id
     * @param productId 대상 상품 id
     * @param request   조합 가격·재고 수정 요청
     * @return 204 No Content
     */
    @PatchMapping("/{productId}/combinations")
    public ResponseEntity<Void> updateCombinations(
            @CurrentUserId UUID sellerId,
            @PathVariable UUID productId,
            @Valid @RequestBody UpdateCombinationPriceStockRequest request
    ) {
        updateCombinationPriceStockUseCase.update(request.toCommand(sellerId, productId));
        return ResponseEntity.noContent().build();
    }

    /**
     * 판매 상태를 전환한다(ON_SALE ↔ SUSPENDED).
     *
     * @param sellerId  인증된 판매자 id
     * @param productId 대상 상품 id
     * @param request   상태 전환 요청
     * @return 204 No Content
     */
    @PatchMapping("/{productId}/status")
    public ResponseEntity<Void> changeStatus(
            @CurrentUserId UUID sellerId,
            @PathVariable UUID productId,
            @Valid @RequestBody ChangeSaleStatusRequest request
    ) {
        changeProductSaleStatusUseCase.change(request.toCommand(sellerId, productId));
        return ResponseEntity.noContent().build();
    }

    /**
     * 상품을 논리 삭제한다(되돌리기 불가). 본문이 없으므로 expectedVersion은 쿼리 파라미터로 받는다.
     *
     * @param sellerId        인증된 판매자 id
     * @param productId       삭제할 상품 id
     * @param expectedVersion 클라이언트가 본 상품 version(stale-form 충돌 감지)
     * @return 204 No Content
     */
    @DeleteMapping("/{productId}")
    public ResponseEntity<Void> delete(
            @CurrentUserId UUID sellerId,
            @PathVariable UUID productId,
            @RequestParam @NotNull Long expectedVersion
    ) {
        deleteProductUseCase.delete(new DeleteProductCommand(productId, sellerId, expectedVersion));
        return ResponseEntity.noContent().build();
    }
}
