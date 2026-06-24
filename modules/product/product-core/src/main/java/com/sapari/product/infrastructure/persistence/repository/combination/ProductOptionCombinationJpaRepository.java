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
     * 상품의 판매가능 조합을 한 번의 UPDATE로 일괄 단종한다(은퇴 시 건당 save N+1 방지). 벌크 갱신이라 @Version·영속성 컨텍스트를 우회한다.
     *
     * @return 단종 처리된 행 수
     */
    @Modifying
    @Query("""
            UPDATE ProductOptionCombinationEntity c
               SET c.isAvailable = false, c.updatedAt = :now
             WHERE c.productId = :productId AND c.isAvailable = true
            """)
    int discontinueAllByProductId(@Param("productId") UUID productId, @Param("now") Instant now);
}
