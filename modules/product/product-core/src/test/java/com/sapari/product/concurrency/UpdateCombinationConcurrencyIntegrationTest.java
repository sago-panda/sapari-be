package com.sapari.product.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.sapari.product.domain.model.combination.CombinationKey;
import com.sapari.product.domain.model.combination.ProductOptionCombination;
import com.sapari.product.domain.model.combination.Sku;
import com.sapari.product.domain.model.combination.Stock;
import com.sapari.product.domain.model.product.Product;
import com.sapari.product.domain.repository.combination.ProductOptionCombinationRepository;
import com.sapari.product.domain.repository.product.ProductRepository;
import com.sapari.product.infrastructure.persistence.repository.combination.ProductOptionCombinationJpaRepository;
import com.sapari.product.infrastructure.persistence.repository.product.ProductJpaRepository;
import com.sapari.product.support.ConcurrentTransactionTestSupport;
import com.sapari.product.support.ProductFixtures;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * 조합 가격·재고 수정 use case({@code UpdateCombinationPriceStockService})가 기반하는 read-modify-write 경로의 <b>동시성</b>을 검증한다.
 *
 * <p>해당 경로는 {@code findById(읽기) → 도메인 전이 → save(갱신)} 순으로 동작한다. 두 트랜잭션이 같은 조합을 동시에 수정할 때 lost
 * update가 발생하면 안 된다 — 두 변경이 모두 반영되거나, 한쪽이 낙관적 락 충돌로 거부되어야 한다(조용한 유실 금지).
 *
 * <p>2개 워커가 같은 조합을 동시에(한쪽은 가격, 한쪽은 재고) 수정하되, {@link ConcurrentTransactionTestSupport}의 배리어로 "둘 다 읽은
 * 뒤에 쓰기"를 강제해 충돌 인터리빙을 결정적으로 만든다. 낙관적 락(@Version) 도입 전에는 한쪽 변경이 조용히 사라져 이 단언이 실패했고, 도입 후에는 한쪽이 충돌로 거부되어
 * 통과한다.
 */
@DisplayName("조합 가격·재고 수정 동시성 — lost update 방지")
class UpdateCombinationConcurrencyIntegrationTest extends ConcurrentTransactionTestSupport {

    private static final UUID SELLER = UUID.fromString("0a0a0a0a-0000-0000-0000-0000000c0c0c");
    private static final Long CATEGORY = 999_001L;
    private static final Instant NOW = Instant.parse("2026-06-24T00:00:00Z");
    private static final int INITIAL_PRICE = 1_000;
    private static final int INITIAL_STOCK = 100;
    private static final int NEW_PRICE = 2_000;
    private static final int NEW_STOCK = 500;

    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private ProductOptionCombinationRepository combinationRepository;
    @Autowired
    private ProductJpaRepository productJpaRepository;
    @Autowired
    private ProductOptionCombinationJpaRepository combinationJpaRepository;

    private UUID productId;
    private UUID combinationId;

    /**
     * 두 트랜잭션이 같은 조합을 동시에 수정(한쪽 가격, 한쪽 재고)하면, 두 변경이 모두 반영되거나 한쪽이 충돌로 거부되어야 한다. 락이 없으면 나중 커밋이 먼저 것을 덮어써
     * 한쪽이 조용히 사라진다(lost update).
     */
    @Test
    @DisplayName("같은 조합을 동시에 가격/재고 수정해도 변경이 유실되지 않는다")
    void concurrentPriceAndStockUpdate_mustNotLoseAnUpdate() {
        persistCombination();

        List<Throwable> errors = runConcurrently(List.of(
                awaitBarrier -> {
                    ProductOptionCombination combination = readCombination();
                    awaitBarrier.run();
                    combinationRepository.save(combination.changePrice(NEW_PRICE, null, NOW));
                },
                awaitBarrier -> {
                    ProductOptionCombination combination = readCombination();
                    awaitBarrier.run();
                    combinationRepository.save(combination.changeStock(NEW_STOCK, NOW));
                }));

        ProductOptionCombination after = inNewTransaction(this::readCombination);
        boolean priceApplied = after.price().equals(NEW_PRICE);
        boolean stockApplied = after.stock().stock().equals(NEW_STOCK);
        // in-flight 레이스는 커밋 시 @Version이 OptimisticLockingFailureException으로 잡는다(웹 레이어 409로 매핑 예정).
        boolean conflictRaised = anyErrorIsInstanceOf(errors, OptimisticLockingFailureException.class);

        // 안전 속성: (두 변경 모두 반영) OR (한쪽이 낙관적 락 충돌로 거부). 둘 다 아니면 = silent lost update.
        assertThat((priceApplied && stockApplied) || conflictRaised)
                .as("동시 수정에서 silent lost update가 발생하면 안 된다. "
                        + "최종 상태=(price=%s, stock=%s), priceApplied=%s, stockApplied=%s, errors=%s",
                        after.price(), after.stock().stock(), priceApplied, stockApplied, errors)
                .isTrue();
    }

    /**
     * 조합을 도메인 모델로 읽는다(낙관적 락 검사를 위해 version을 함께 적재).
     */
    private ProductOptionCombination readCombination() {
        return combinationRepository.findById(combinationId).orElseThrow();
    }

    /**
     * 상품 + 조합 1건을 독립 트랜잭션으로 커밋해, 워커 스레드에서 보이도록 한다.
     */
    private void persistCombination() {
        UUID[] ids = inNewTransaction(() -> {
            Product product = productRepository.save(ProductFixtures.minimal(SELLER, CATEGORY));
            ProductOptionCombination combination = ProductOptionCombination.builder()
                    .productId(product.id())
                    .combinationKey(CombinationKey.of("concurrency-1"))
                    .sku(Sku.of("SKU-CC-1"))
                    .price(INITIAL_PRICE)
                    .stock(Stock.of(INITIAL_STOCK, 0))
                    .isAvailable(true)
                    .optionValueIds(List.of())
                    .createdAt(NOW)
                    .updatedAt(NOW)
                    .build();
            ProductOptionCombination saved = combinationRepository.saveAll(List.of(combination)).get(0);
            return new UUID[] {product.id(), saved.id()};
        });
        this.productId = ids[0];
        this.combinationId = ids[1];
    }

    /**
     * 테스트가 독립 트랜잭션으로 커밋한 상품·조합 행을 제거한다(테스트는 롤백되지 않으므로 수동 정리).
     */
    @AfterEach
    void cleanup() {
        inNewTransaction(() -> {
            if (combinationId != null && combinationJpaRepository.existsById(combinationId)) {
                combinationJpaRepository.deleteById(combinationId);
            }
            if (productId != null && productJpaRepository.existsById(productId)) {
                productJpaRepository.deleteById(productId);
            }
        });
    }
}
