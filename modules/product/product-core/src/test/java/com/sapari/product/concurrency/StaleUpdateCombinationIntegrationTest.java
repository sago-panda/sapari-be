package com.sapari.product.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.sapari.product.domain.exception.ProductConcurrentModificationException;
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

/**
 * stale-form 덮어쓰기를 낙관적 락이 거부함을 검증한다(영속 메커니즘).
 *
 * <p>사용자 think-time을 가로지르는 순차 시나리오:
 * <ol>
 *   <li>T0: 요청 A가 폼을 위해 조합을 읽고(가격 1000, version v0) 그 스냅샷 기준으로 수정값을 정한다.</li>
 *   <li>T1: 요청 B가 가격을 2000으로 수정·커밋한다(version v1).</li>
 *   <li>T2: 요청 A가 T0 스냅샷(version v0)을 들고 가격 1500을 제출한다.</li>
 * </ol>
 *
 * <p>A가 들고 온 version(v0)이 현재 행 version(v1)과 달라, 어댑터가 stale 제출을 {@link ProductConcurrentModificationException}으로
 * 거부한다 — B의 변경(2000)이 보존된다. 엔티티 {@code @Version}만으로는 이걸 못 막는다(갱신 트랜잭션이 최신 version을 다시 읽으므로). 도메인이
 * <b>읽은 시점 version</b>을 들고 와 수동 비교해야 비로소 성립한다.
 *
 * <p>이 테스트는 그 <b>영속 메커니즘</b>을 검증한다(스냅샷이 version을 보존 = 클라이언트 왕복을 흉내). 실제 end-to-end 활성화(읽기 View에
 * version 노출 → Update Command의 {@code expectedVersion} → 서비스가 도메인 version에 override)는 ③에서 완성한다 — 설계문서 §13.
 */
@DisplayName("조합 stale-form 덮어쓰기 — 버전 왕복 시 낙관적 락이 거부(검증)")
class StaleUpdateCombinationIntegrationTest extends ConcurrentTransactionTestSupport {

    private static final UUID SELLER = UUID.fromString("0c0c0c0c-0000-0000-0000-0000000e0e0e");
    private static final Long CATEGORY = 999_003L;
    private static final Instant NOW = Instant.parse("2026-06-24T00:00:00Z");
    private static final int INITIAL_PRICE = 1_000;
    private static final int B_PRICE = 2_000;
    private static final int A_STALE_PRICE = 1_500;

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
     * A가 읽은 시점 version(T0)을 들고 제출하면, 그 사이 B가 수정·커밋(T1)해 version이 올라간 경우 A의 stale 제출(T2)은
     * {@link ProductConcurrentModificationException}으로 거부되어 B의 변경이 보존된다.
     */
    @Test
    @DisplayName("A 읽기 → B 수정·커밋 → A의 stale 제출이 거부된다(B 보존)")
    void staleSubmit_isRejected() {
        persistCombination();

        // T0: 요청 A가 폼을 위해 읽은 스냅샷(가격 1000). 이 스냅샷 기준으로 수정값을 정한다.
        ProductOptionCombination aSnapshot = inNewTransaction(this::readCombination);

        // T1: 요청 B가 가격을 2000으로 수정·커밋
        inNewTransaction(() -> combinationRepository.save(readCombination().changePrice(B_PRICE, null, NOW)));

        // T2: 요청 A가 T0 스냅샷 기준으로 가격 1500 제출
        Throwable thrown = catchThrowable(() ->
                inNewTransaction(() -> combinationRepository.save(aSnapshot.changePrice(A_STALE_PRICE, null, NOW))));

        ProductOptionCombination after = inNewTransaction(this::readCombination);

        // 안전 속성: stale 제출은 충돌로 거부되어 B의 변경이 보존되어야 한다(거부 안 되면 B가 유실됨).
        assertThat(thrown)
                .as("stale 제출은 ProductConcurrentModificationException으로 거부되어야 함 (거부 실패 시 B 유실, 최종 price=%s)",
                        after.price())
                .isInstanceOf(ProductConcurrentModificationException.class);
    }

    private ProductOptionCombination readCombination() {
        return combinationRepository.findById(combinationId).orElseThrow();
    }

    private void persistCombination() {
        UUID[] ids = inNewTransaction(() -> {
            Product product = productRepository.save(ProductFixtures.minimal(SELLER, CATEGORY));
            ProductOptionCombination combination = ProductOptionCombination.builder()
                    .productId(product.id())
                    .combinationKey(CombinationKey.of("stale-1"))
                    .sku(Sku.of("SKU-STALE-1"))
                    .price(INITIAL_PRICE)
                    .stock(Stock.of(100, 0))
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
