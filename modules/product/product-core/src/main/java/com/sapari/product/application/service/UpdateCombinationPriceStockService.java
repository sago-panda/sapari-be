package com.sapari.product.application.service;

import com.sapari.global.time.TimeProvider;
import com.sapari.product.command.CombinationUpdateCommand;
import com.sapari.product.command.UpdateCombinationPriceStockCommand;
import com.sapari.product.domain.exception.CombinationNotFoundException;
import com.sapari.product.domain.exception.InvalidProductStateException;
import com.sapari.product.domain.exception.ProductAccessDeniedException;
import com.sapari.product.domain.exception.ProductNotFoundException;
import com.sapari.product.domain.model.combination.CombinationKey;
import com.sapari.product.domain.model.combination.ProductOptionCombination;
import com.sapari.product.domain.model.product.Product;
import com.sapari.product.domain.model.product.ProductOptionValueModel;
import com.sapari.product.domain.repository.combination.ProductOptionCombinationRepository;
import com.sapari.product.domain.repository.product.ProductRepository;
import com.sapari.product.port.UpdateCombinationPriceStockUseCase;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 조합 가격·재고 수정 유스케이스. 지정된 조합들의 가격·재고·판매여부를 즉시 반영한다(재승인 불필요).
 *
 * <p>가격·재고·판매여부가 바뀌므로 상품 캐시 컬럼(min_price·has_stock)을 트랜잭션 끝에서 다시 계산한다(품절·재입고 반영).
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
     * @throws InvalidProductStateException 옵션 교체로 은퇴한 조합의 판매를 재개하려는 경우
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
        // 조합을 건별 findById(N+1) 대신 한 번에 모아 조회
        List<UUID> combinationIds = command.combinations()
                .stream()
                .map(CombinationUpdateCommand::combinationId)
                .toList();
        Map<UUID, ProductOptionCombination> combinationsById = combinationRepository.findAllById(combinationIds)
                .stream()
                .collect(Collectors.toMap(ProductOptionCombination::id, found -> found));

        // 조합 id 순으로 저장한다 — save가 조합마다 즉시 반영돼 행 잠금을 잡으므로, 요청 순서를 따르면 [c1,c2]·[c2,c1] 동시 요청이 교착한다
        List<CombinationUpdateCommand> orderedUpdates = command.combinations()
                .stream()
                .sorted(Comparator.comparing(CombinationUpdateCommand::combinationId))
                .toList();
        for (CombinationUpdateCommand update : orderedUpdates) {
            // 조합이 존재하고 이 상품 소속인지 확인 (타 상품 조합 위변조 차단)
            ProductOptionCombination combination = combinationsById.get(update.combinationId());
            if (combination == null || !combination.productId().equals(product.id())) {
                throw new CombinationNotFoundException("옵션 조합을 찾을 수 없습니다: " + update.combinationId());
            }
            // 옵션 교체로 은퇴한 조합은 현재 옵션 트리에서 고를 수 없으므로 판매를 재개할 수 없다(판매자 단종 조합만 재개 가능)
            if (Boolean.TRUE.equals(update.isAvailable()) && !belongsToCurrentOptions(combination, product)) {
                throw new InvalidProductStateException("옵션 교체로 은퇴한 조합은 다시 판매할 수 없습니다: " + combination.id());
            }
            combinationRepository.save(applyChanges(combination, update, now));
        }
        // 바뀐 가격·재고·판매여부로 최저가·재고유무 캐시를 다시 계산 (조합 변경을 먼저 반영한 뒤 상품 행을 잠가 계산)
        productRepository.refreshStockPriceCache(product.id(), now);
    }

    /**
     * 조합이 상품의 현재 옵션 구성에 속하는지 판단한다. 단일 상품은 기본 조합 키({@code DEFAULT})여야 하고, 옵션 상품은 옵션 타입마다 현재 옵션값 하나씩으로
     * 이뤄져야 한다 — 옵션 교체는 옵션값을 새 id로 교체하고 기본 조합 키를 비우므로, 은퇴한 조합은 이 조건을 만족하지 못한다.
     */
    private static boolean belongsToCurrentOptions(ProductOptionCombination combination, Product product) {
        if (product.optionTypes().isEmpty()) {
            return CombinationKey.DEFAULT.equals(combination.combinationKey().value());
        }
        Set<UUID> currentValueIds = product.optionTypes()
                .stream()
                .flatMap(type -> type.values().stream())
                .map(ProductOptionValueModel::id)
                .collect(Collectors.toSet());
        return combination.optionValueIds().size() == product.optionTypes().size()
                && currentValueIds.containsAll(combination.optionValueIds());
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
        // 클라이언트가 본 조합 version으로 덮어써야 저장 시 조합별 stale 비교가 동작한다(§13)
        return changed.toBuilder()
                .version(update.expectedVersion())
                .build();
    }
}
