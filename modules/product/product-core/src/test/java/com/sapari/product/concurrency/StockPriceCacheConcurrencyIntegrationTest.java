package com.sapari.product.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.sapari.product.domain.model.combination.CombinationKey;
import com.sapari.product.domain.model.combination.ProductOptionCombination;
import com.sapari.product.domain.model.combination.Stock;
import com.sapari.product.domain.model.product.Product;
import com.sapari.product.domain.repository.combination.ProductOptionCombinationRepository;
import com.sapari.product.domain.repository.product.ProductRepository;
import com.sapari.product.infrastructure.persistence.repository.combination.ProductOptionCombinationJpaRepository;
import com.sapari.product.infrastructure.persistence.repository.product.ProductJpaRepository;
import com.sapari.product.support.ConcurrentTransactionTestSupport;
import com.sapari.product.support.ProductFixtures;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 상품 파생 캐시(min_price·has_stock) 재계산의 <b>동시성</b>을 검증한다.
 *
 * <p>같은 상품의 서로 다른 조합을 두 트랜잭션이 동시에 바꾸면, 각 트랜잭션은 상대의 미커밋 변경을 볼 수 없다. 캐시를 잠금 없이 계산하면 나중에 커밋한 쪽이 상대 변경을 모르는 값으로
 * 캐시를 덮어써 lost update가 난다(예: 두 조합이 모두 품절됐는데 has_stock=true). 재계산은 조합 변경을 먼저 반영하고 상품 행을 잠근 뒤 새 문장으로 계산하므로,
 * 뒤 트랜잭션은 앞 트랜잭션의 커밋된 조합까지 반영해야 한다.
 *
 * <p>인터리빙을 고정한다: 두 워커가 각자 조합 변경을 DB에 반영한 뒤 배리어에서 만나고, 워커 1은 캐시를 재계산한 뒤 <b>커밋하지 않고</b> 잠시 머문다. 워커 2는
 * 그 사이(워커 1 미커밋 상태)에 재계산을 시작한다. 잠금 없이 계산하면 워커 2의 계산 문장은 워커 1 커밋 전 스냅샷(조합 A 재고 있음)으로 캐시를 덮어써 실패한다.
 */
@DisplayName("상품 캐시(min_price·has_stock) 재계산 동시성 — lost update 방지")
class StockPriceCacheConcurrencyIntegrationTest extends ConcurrentTransactionTestSupport {

    private static final UUID SELLER = UUID.fromString("0a0a0a0a-0000-0000-0000-0000000d0d0d");
    private static final Long CATEGORY = 999_002L;
    private static final Instant NOW = Instant.parse("2026-09-15T00:00:00Z");
    // 워커 1이 재계산 후 커밋을 미루는 시간 / 워커 2가 그 창 안에서 재계산을 시작하기까지의 지연 — 지연 < 보류라 순서가 고정된다
    private static final long FIRST_HOLDS_MILLIS = 1_500;
    private static final long SECOND_STARTS_AFTER_MILLIS = 300;

    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private ProductOptionCombinationRepository combinationRepository;
    @Autowired
    private ProductJpaRepository productJpaRepository;
    @Autowired
    private ProductOptionCombinationJpaRepository combinationJpaRepository;

    @PersistenceContext
    private EntityManager em;

    private UUID productId;
    private UUID comboA;
    private UUID comboB;

    /**
     * 두 트랜잭션이 같은 상품의 조합 A·B를 동시에 품절시키면, 최종 캐시는 두 변경을 모두 반영해 has_stock=false·min_price=null이어야 한다.
     */
    @Test
    @DisplayName("같은 상품의 두 조합을 동시에 품절시켜도 캐시가 한쪽 변경을 잃지 않는다 (최종 has_stock=false)")
    void concurrentSoldOutOnDifferentCombinations_mustNotLoseCacheUpdate() {
        persistProductWithTwoCombinations();

        List<Throwable> errors = runConcurrently(List.of(
                awaitBarrier -> {
                    soldOut(comboA, awaitBarrier);
                    productRepository.refreshStockPriceCache(productId, NOW);
                    // 상품 행을 쥔 채 커밋을 미룬다 — 워커 2가 이 사이에 재계산을 시작하게 한다
                    Thread.sleep(FIRST_HOLDS_MILLIS);
                },
                awaitBarrier -> {
                    soldOut(comboB, awaitBarrier);
                    Thread.sleep(SECOND_STARTS_AFTER_MILLIS);
                    productRepository.refreshStockPriceCache(productId, NOW);
                }));

        Product after = inNewTransaction(() -> productRepository.findById(productId).orElseThrow());
        assertThat(errors).as("서로 다른 조합 변경은 충돌 없이 모두 커밋되어야 한다").isEmpty();
        assertThat(after.hasStock())
                .as("두 조합이 모두 품절됐는데 has_stock이 true면 한쪽 트랜잭션이 상대 변경을 모르고 캐시를 덮어쓴 것(lost update)")
                .isFalse();
        assertThat(after.minPrice()).isNull();
    }

    /**
     * 조합 하나를 품절시키고 그 변경을 DB에 반영(조합 행 잠금)한 뒤, 배리어에서 상대 워커를 기다린다.
     */
    private void soldOut(UUID combinationId, Runnable awaitBarrier) {
        ProductOptionCombination combination = combinationRepository.findById(combinationId).orElseThrow();
        combinationRepository.save(combination.changeStock(0, NOW));
        em.flush();
        awaitBarrier.run();
    }

    /**
     * 상품 + 재고 있는 조합 2건을 독립 트랜잭션으로 커밋하고 캐시를 초기화한다(has_stock=true).
     */
    private void persistProductWithTwoCombinations() {
        UUID[] ids = inNewTransaction(() -> {
            Product product = productRepository.save(ProductFixtures.minimal(SELLER, CATEGORY));
            List<ProductOptionCombination> saved = combinationRepository.saveAll(List.of(
                    combination(product.id(), "cache-a", 1_000),
                    combination(product.id(), "cache-b", 2_000)));
            productRepository.refreshStockPriceCache(product.id(), NOW);
            return new UUID[] {product.id(), saved.get(0).id(), saved.get(1).id()};
        });
        this.productId = ids[0];
        this.comboA = ids[1];
        this.comboB = ids[2];
        Product before = inNewTransaction(() -> productRepository.findById(productId).orElseThrow());
        assertThat(before.hasStock()).isTrue();
        assertThat(before.minPrice()).isEqualTo(1_000);
    }

    private static ProductOptionCombination combination(UUID productId, String key, int price) {
        return ProductOptionCombination.create(
                productId, CombinationKey.of(key), null, null, price, Stock.of(10, 0), List.of(), NOW);
    }

    /**
     * 테스트가 독립 트랜잭션으로 커밋한 상품·조합 행을 제거한다(테스트는 롤백되지 않으므로 수동 정리).
     */
    @AfterEach
    void cleanup() {
        inNewTransaction(() -> {
            for (UUID id : new UUID[] {comboA, comboB}) {
                if (id != null && combinationJpaRepository.existsById(id)) {
                    combinationJpaRepository.deleteById(id);
                }
            }
            if (productId != null && productJpaRepository.existsById(productId)) {
                productJpaRepository.deleteById(productId);
            }
        });
    }
}
