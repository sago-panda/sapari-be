package com.sapari.product.application.service;

import com.sapari.global.time.TimeProvider;
import com.sapari.product.command.UpdateProductOptionsCommand;
import com.sapari.product.domain.exception.ProductAccessDeniedException;
import com.sapari.product.domain.exception.ProductNotFoundException;
import com.sapari.product.domain.model.combination.CombinationGenerator;
import com.sapari.product.domain.model.combination.ProductOptionCombination;
import com.sapari.product.domain.model.product.Product;
import com.sapari.product.domain.repository.combination.ProductOptionCombinationRepository;
import com.sapari.product.domain.repository.product.ProductRepository;
import com.sapari.product.port.UpdateProductOptionsUseCase;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상품 옵션 수정 유스케이스. 옵션 타입/값을 교체하고 옵션 조합을 재생성한다.
 *
 * <p>기존 조합은 주문이 id로 직접 참조하므로 물리 삭제하지 않고 단종({@code isAvailable=false}) 처리한 뒤, 새 옵션값으로 새 조합을 생성한다.
 * 새 옵션값은 새 id를 받으므로 조합 키가 달라 기존 조합과 충돌하지 않는다.
 *
 * <p>옵션 교체는 재검수 대상이 아니다(가격·재고·구조 변경은 즉시 반영, PRD §18.3). 상품 캐시 컬럼(min_price·has_stock)
 * 재계산은 후속 증분에서 추가한다.
 */
@Service
@RequiredArgsConstructor
public class UpdateProductOptionsService implements UpdateProductOptionsUseCase {

    private final ProductRepository productRepository;
    private final ProductOptionCombinationRepository combinationRepository;
    private final TimeProvider timeProvider;

    /**
     * 상품 옵션을 교체하고 조합을 재생성한다. 존재·소유권을 확인하고, 기존 조합 단종 → 상품 옵션 교체 저장 → 새 조합 생성·저장 순으로 진행한다.
     *
     * @throws ProductNotFoundException     상품이 없거나 삭제된 경우
     * @throws ProductAccessDeniedException 요청 판매자가 소유자가 아닌 경우
     */
    @Override
    @Transactional
    public void update(UpdateProductOptionsCommand command) {
        Product product = productRepository.findActiveById(command.productId())
                .orElseThrow(() -> new ProductNotFoundException(
                        "상품을 찾을 수 없습니다: " + command.productId()));

        if (!product.sellerId().equals(command.sellerId())) {
            throw new ProductAccessDeniedException(
                    "상품 소유자가 아닙니다: product=" + command.productId() + ", seller=" + command.sellerId());
        }

        Instant now = timeProvider.now();

        // 기존 조합 일괄 단종 — 주문 참조 보존을 위해 삭제 대신 isAvailable=false로 은퇴 (건당 save N+1 대신 벌크 UPDATE)
        combinationRepository.discontinueAllByProductId(product.id(), now);

        // 새 옵션 타입/값으로 교체 저장 → 옵션값 id 확정
        Product withNewOptions = product.toBuilder()
                .optionTypes(ProductOptionCommandMapper.toOptionTypeModels(command.optionTypes()))
                .updatedAt(now)
                .build();
        Product saved = productRepository.save(withNewOptions);

        // 확정된 새 옵션값 id로 조합 재생성 후 일괄 저장 (신규 조합이라 건당 INSERT 왕복(N+1) 방지)
        List<ProductOptionCombination> combinations = CombinationGenerator.generate(
                saved.id(),
                saved.basePrice(),
                saved.optionTypes(),
                List.of(),
                List.of(),
                command.defaultStock(),
                now);
        if (!combinations.isEmpty()) {
            combinationRepository.saveAll(combinations);
        }
    }
}
