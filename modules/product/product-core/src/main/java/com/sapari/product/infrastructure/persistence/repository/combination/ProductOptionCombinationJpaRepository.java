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
     * 상품의 조합을 판매여부와 무관하게 한 번의 UPDATE로 일괄 은퇴시킨다(건당 save N+1 방지). 이미 단종된 행도 잠그고 version을 올려 판매 재개 PATCH를 막는다.
     *
     * <p>벌크는 PC를 우회하므로 {@code version + 1}을 명시해 동시 stale 저장을 낙관적 락 충돌로 막는다.
     * {@code flushAutomatically=true}로 호출 전 대기 변경을 먼저 반영하고, {@code clearAutomatically=true}로 호출 후
     * 1차 캐시를 비워, 같은 트랜잭션에서 조합을 로드해 둔 호출자가 단종 전 stale 캐시본을 보거나 version 불일치로 충돌하는 일을 막는다.
     *
     * @return 은퇴 처리된 행 수(이미 단종된 행 포함)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE ProductOptionCombinationEntity c
               SET c.isAvailable = false, c.updatedAt = :now, c.version = c.version + 1
             WHERE c.productId = :productId
            """)
    int discontinueAllByProductId(@Param("productId") UUID productId, @Param("now") Instant now);

    /**
     * 상품의 기본 조합 키({@code DEFAULT})를 {@code DEFAULT#조합id}로 바꿔 비운다(판매여부 무관). 옵션 교체로 은퇴한 기본 조합이
     * {@code (product_id, combination_key)} 유니크를 계속 점유해 새 기본 조합 생성을 막지 않도록 한다. 동시 조합 수정이 옛 키로 덮어쓰지 못하게 version을 올린다.
     *
     * @return 키를 비운 행 수(0 또는 1)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE ProductOptionCombinationEntity c
               SET c.combinationKey = CONCAT(c.combinationKey, '#', CAST(c.id AS String)),
                   c.updatedAt = :now, c.version = c.version + 1
             WHERE c.productId = :productId AND c.combinationKey = :defaultKey
            """)
    int releaseDefaultKey(
            @Param("productId") UUID productId, @Param("defaultKey") String defaultKey, @Param("now") Instant now);
}
