package com.sapari.product.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.sapari.product.domain.model.product.Product;
import com.sapari.product.domain.model.product.ProductOptionModel;
import com.sapari.product.domain.model.product.ProductStatus;
import com.sapari.product.domain.repository.product.ProductRepository;
import com.sapari.product.infrastructure.persistence.repository.product.ProductJpaRepository;
import com.sapari.product.support.ConcurrentTransactionTestSupport;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * 상품 루트 수정 use case({@code UpdateProductInfo}/{@code ChangeSaleStatus}/{@code Delete})가 기반하는 read-modify-write 경로의
 * <b>동시성</b>을 검증한다.
 *
 * <p>두 워커가 같은 상품을 동시에(한쪽은 판매중지, 한쪽은 삭제) 수정하되, {@link ConcurrentTransactionTestSupport}의 배리어로 "둘 다 읽은
 * 뒤에 쓰기"를 강제한다. 같은 행을 두 트랜잭션이 동시에 갱신하므로 커밋 시 엔티티 {@code @Version}이 한쪽을 낙관적 락 충돌
 * ({@code OptimisticLockingFailureException})로 거부한다 — 한 변경이 조용히 사라지거나 두 변경이 뒤섞여 적용되지 않는다.
 */
@DisplayName("상품 루트 수정 동시성 — lost update 방지")
class UpdateProductConcurrencyIntegrationTest extends ConcurrentTransactionTestSupport {

    private static final UUID SELLER = UUID.fromString("0b0b0b0b-0000-0000-0000-0000000d0d0d");
    private static final Long CATEGORY = 999_002L;
    private static final Instant NOW = Instant.parse("2026-06-24T00:00:00Z");

    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private ProductJpaRepository productJpaRepository;

    private UUID productId;

    /**
     * 두 트랜잭션이 같은 상품을 동시에 수정하면, 충돌이 감지되어(한쪽이 거부됨) 조용한 유실·뒤섞임이 없어야 한다.
     */
    @Test
    @DisplayName("같은 상품을 동시에 판매중지/삭제하면 충돌이 감지되어 변경이 유실·혼합되지 않는다")
    void concurrentProductUpdate_detectsConflict() {
        persistOnSaleProduct();

        List<Throwable> errors = runConcurrently(List.of(
                awaitBarrier -> {
                    Product product = readProduct();
                    awaitBarrier.run();
                    productRepository.save(product.suspend(NOW));
                },
                awaitBarrier -> {
                    Product product = readProduct();
                    awaitBarrier.run();
                    productRepository.save(product.softDelete(NOW));
                }));

        Product after = inNewTransaction(this::readProduct);
        boolean suspended = after.status() == ProductStatus.SUSPENDED;
        boolean deleted = after.isDeleted();
        // in-flight 레이스는 커밋 시 @Version이 OptimisticLockingFailureException으로 잡는다(웹 레이어 409로 매핑 예정).
        boolean conflictRaised = anyErrorIsInstanceOf(errors, OptimisticLockingFailureException.class);

        // 안전 속성 1) 동시 수정은 충돌로 감지되어야 한다(조용한 유실 금지).
        assertThat(conflictRaised)
                .as("동시 수정이 충돌로 감지되어야 한다. 최종=(status=%s, deleted=%s), errors=%s",
                        after.status(), deleted, errors)
                .isTrue();
        // 안전 속성 2) 패자 트랜잭션이 롤백되어 두 변경이 동시에 적용되지 않아야 한다.
        assertThat(suspended && deleted)
                .as("패자 트랜잭션이 롤백되어 한 변경만 적용되어야 한다. 최종=(status=%s, deleted=%s)",
                        after.status(), deleted)
                .isFalse();
    }

    /**
     * 상품을 도메인 모델로 읽는다(낙관적 락 검사를 위해 version을 함께 적재).
     */
    private Product readProduct() {
        return productRepository.findById(productId).orElseThrow();
    }

    /**
     * 판매 가능(ON_SALE) 상품 1건을 독립 트랜잭션으로 커밋해, 워커 스레드에서 보이도록 한다.
     */
    private void persistOnSaleProduct() {
        productId = inNewTransaction(() -> {
            Product onSale = Product.create(SELLER, CATEGORY, "동시성 상품", "설명", 10_000, null, 0,
                            ProductOptionModel.COMBINATION, NOW)
                    .approve(NOW);
            return productRepository.save(onSale).id();
        });
    }

    /**
     * 테스트가 독립 트랜잭션으로 커밋한 상품 행을 제거한다(테스트는 롤백되지 않으므로 수동 정리).
     */
    @AfterEach
    void cleanup() {
        inNewTransaction(() -> {
            if (productId != null && productJpaRepository.existsById(productId)) {
                productJpaRepository.deleteById(productId);
            }
        });
    }
}
