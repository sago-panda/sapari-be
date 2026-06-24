package com.sapari.product.application.service;

import com.sapari.global.time.TimeProvider;
import com.sapari.product.command.CombinationUpdateCommand;
import com.sapari.product.command.UpdateCombinationPriceStockCommand;
import com.sapari.product.domain.exception.CombinationNotFoundException;
import com.sapari.product.domain.exception.ProductAccessDeniedException;
import com.sapari.product.domain.exception.ProductNotFoundException;
import com.sapari.product.domain.model.combination.ProductOptionCombination;
import com.sapari.product.domain.model.product.Product;
import com.sapari.product.domain.repository.combination.ProductOptionCombinationRepository;
import com.sapari.product.domain.repository.product.ProductRepository;
import com.sapari.product.port.UpdateCombinationPriceStockUseCase;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 조합 가격·재고 수정 유스케이스. 지정된 조합들의 가격·재고·판매여부를 즉시 반영한다(재승인 불필요).
 *
 * <p>상품 캐시 컬럼(has_stock·min_price) 재계산은 후속 증분에서 추가한다.
 */
@Service
@RequiredArgsConstructor
public class UpdateCombinationPriceStockService implements UpdateCombinationPriceStockUseCase {

    private final ProductRepository productRepository;
    private final ProductOptionCombinationRepository combinationRepository;
    private final TimeProvider timeProvider;

    /**
     * 조합들의 가격·재고·판매여부를 변경한다. 상품 존재·소유권을 확인하고, 각 조합이 해당 상품 소속인지 검증한 뒤 변경분을 적용·저장한다.
     *
     * @throws ProductNotFoundException     상품이 없거나 삭제된 경우
     * @throws ProductAccessDeniedException 요청 판매자가 소유자가 아닌 경우
     * @throws CombinationNotFoundException 조합이 없거나 이 상품 소속이 아닌 경우
     */
    @Override
    @Transactional
    public void update(UpdateCombinationPriceStockCommand command) {
        Product product = productRepository.findActiveById(command.productId())
                .orElseThrow(() -> new ProductNotFoundException(
                        "상품을 찾을 수 없습니다: " + command.productId()));

        if (!product.sellerId().equals(command.sellerId())) {
            throw new ProductAccessDeniedException(
                    "상품 소유자가 아닙니다: product=" + command.productId() + ", seller=" + command.sellerId());
        }

        Instant now = timeProvider.now();
        for (CombinationUpdateCommand update : command.combinations()) {
            // 조합이 존재하고 이 상품 소속인지 확인 (타 상품 조합 위변조 차단)
            ProductOptionCombination combination = combinationRepository.findById(update.combinationId())
                    .filter(found -> found.productId().equals(product.id()))
                    .orElseThrow(() -> new CombinationNotFoundException(
                            "옵션 조합을 찾을 수 없습니다: " + update.combinationId()));

            combinationRepository.save(applyChanges(combination, update, now));
        }
    }

    /**
     * 변경 항목을 조합에 적용한다 — null 필드는 건너뛴다(가격→재고→판매여부 순).
     */
    private ProductOptionCombination applyChanges(
            ProductOptionCombination combination, CombinationUpdateCommand update, Instant now) {
        ProductOptionCombination changed = combination;
        if (update.price() != null) {
            changed = changed.changePrice(update.price(), update.originalPrice(), now);
        }
        if (update.stock() != null) {
            changed = changed.changeStock(update.stock(), now);
        }
        if (update.isAvailable() != null) {
            changed = update.isAvailable() ? changed.makeAvailable(now) : changed.discontinue(now);
        }
        return changed;
    }
}
