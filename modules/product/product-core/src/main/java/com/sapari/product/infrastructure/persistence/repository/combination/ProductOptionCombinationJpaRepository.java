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
     * <p>벌크는 PC를 우회하므로 {@code version + 1}을 명시해 동시 stale 저장을 낙관적 락 충돌로 막는다.
     * {@code flushAutomatically=true}로 호출 전 대기 변경을 먼저 반영하고, {@code clearAutomatically=true}로 호출 후
     * 1차 캐시를 비워, 같은 트랜잭션에서 조합을 로드해 둔 호출자가 단종 전 stale 캐시본을 보거나 version 불일치로 충돌하는 일을 막는다.
     *
     * @return 단종 처리된 행 수
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE ProductOptionCombinationEntity c
               SET c.isAvailable = false, c.updatedAt = :now, c.version = c.version + 1
             WHERE c.productId = :productId AND c.isAvailable = true
            """)
    int discontinueAllByProductId(@Param("productId") UUID productId, @Param("now") Instant now);
}
