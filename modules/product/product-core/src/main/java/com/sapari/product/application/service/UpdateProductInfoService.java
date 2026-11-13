package com.sapari.product.application.service;

import com.sapari.global.time.TimeProvider;
import com.sapari.product.application.port.HtmlSanitizer;
import com.sapari.product.command.UpdateProductInfoCommand;
import com.sapari.product.domain.exception.CategoryNotFoundException;
import com.sapari.product.domain.exception.ProductAccessDeniedException;
import com.sapari.product.domain.exception.ProductNotFoundException;
import com.sapari.product.domain.model.product.Product;
import com.sapari.product.domain.model.product.ProductTagPolicy;
import com.sapari.product.domain.repository.category.CategoryRepository;
import com.sapari.product.domain.repository.product.ProductRepository;
import com.sapari.product.port.UpdateProductInfoUseCase;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상품 기본 정보 수정 유스케이스. 소유권을 확인하고 정보·태그를 교체한 뒤 재검수 대기 상태로 전환한다.
 *
 * <p>옵션·조합은 건드리지 않는다(별도 유스케이스). "재승인 중 기존 정보 판매 유지"는 리비전 스냅샷이 필요해 후속 과제로 둔다.
 */
@Service
@RequiredArgsConstructor
public class UpdateProductInfoService implements UpdateProductInfoUseCase {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final TimeProvider timeProvider;
    private final HtmlSanitizer htmlSanitizer;

    /**
     * 상품 기본 정보를 수정한다. 존재·소유권·태그·카테고리를 검증한 뒤 정보를 갱신하고 태그를 교체해 저장한다.
     *
     * @throws ProductNotFoundException     상품이 없거나 삭제된 경우
     * @throws ProductAccessDeniedException 요청 판매자가 소유자가 아닌 경우
     * @throws CategoryNotFoundException    변경할 카테고리가 없는 경우
     */
    @Override
    @Transactional
    public void update(UpdateProductInfoCommand command) {
        // 삭제된 상품은 없는 것으로 취급
        Product product = productRepository.findActiveById(command.productId())
                .orElseThrow(() -> new ProductNotFoundException(
                        "상품을 찾을 수 없습니다: " + command.productId()));

        // 소유권 확인 — 본인 상품만 수정 가능
        if (!product.sellerId().equals(command.sellerId())) {
            throw new ProductAccessDeniedException(
                    "상품 소유자가 아닙니다: product=" + command.productId() + ", seller=" + command.sellerId());
        }

        // 순수 검증(태그)부터, 그다음 카테고리 존재 확인
        ProductTagPolicy.validate(command.tags());
        if (categoryRepository.findById(command.categoryId()).isEmpty()) {
            throw new CategoryNotFoundException("카테고리를 찾을 수 없습니다: " + command.categoryId());
        }

        // 정보 갱신(→ PENDING_REVIEW) 후 태그 교체. null 태그는 빈 목록으로 정규화
        List<String> tags = command.tags() == null ? List.of() : command.tags();
        Product updated = product.updateInfo(
                        command.categoryId(),
                        command.name(),
                        htmlSanitizer.sanitize(command.description()),
                        command.shippingPolicyId(),
                        command.additionalShippingFee(),
                        timeProvider.now())
                .toBuilder()
                .tags(tags)
                // 클라이언트가 폼에서 본 version으로 덮어써야 저장 시 stale 비교가 동작한다(§13)
                .version(command.expectedVersion())
                .build();
        productRepository.save(updated);
    }
}
