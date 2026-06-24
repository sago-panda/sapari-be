package com.sapari.product.infrastructure.persistence.repository.combination;

import com.sapari.product.infrastructure.persistence.entity.combination.ProductOptionCombinationEntity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@code product_option_combinations} Spring Data JPA 어댑터. 파생 쿼리로 상품별·SKU 조회를 제공한다.
 */
public interface ProductOptionCombinationJpaRepository
        extends JpaRepository<ProductOptionCombinationEntity, UUID> {

    List<ProductOptionCombinationEntity> findByProductId(UUID productId);

    Optional<ProductOptionCombinationEntity> findByProductIdAndSku(UUID productId, String sku);

    /**
     * 상품의 판매가능 조합을 한 번의 UPDATE로 일괄 단종한다(건당 save N+1 방지).
     *
     * <p>벌크는 PC를 우회하므로 {@code version + 1}을 명시해 동시 stale 저장을 낙관적 락 충돌로 막는다. 또한 호출자는
     * 같은 트랜잭션에서 이 조합들을 다시 읽지 말 것 — 읽으면 1차 캐시의 단종 전 상태가 보인다(현 단일 호출자는 재조회 없음).
     *
     * @return 단종 처리된 행 수
     */
    @Modifying
    @Query("""
            UPDATE ProductOptionCombinationEntity c
               SET c.isAvailable = false, c.updatedAt = :now, c.version = c.version + 1
             WHERE c.productId = :productId AND c.isAvailable = true
            """)
    int discontinueAllByProductId(@Param("productId") UUID productId, @Param("now") Instant now);
}
