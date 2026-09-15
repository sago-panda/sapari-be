package com.sapari.product.infrastructure.persistence.repository.product;

import com.sapari.product.infrastructure.persistence.entity.product.ProductEntity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 상품 원장(products) Spring Data 리포지토리 — 루트 엔티티 전용. 자식(태그·이미지·옵션)까지의 애그리거트 조립/해체는 {@link ProductRepositoryImpl}가 담당한다.
 */
public interface ProductJpaRepository extends JpaRepository<ProductEntity, UUID> {
    Optional<ProductEntity> findByIdAndDeletedAtIsNull(UUID id);

    List<ProductEntity> findByCategoryId(Long categoryId);

    /**
     * 상품 행에 쓰기 잠금을 건다({@code SELECT ... FOR UPDATE}). 캐시 재계산을 같은 상품의 동시 조합 변경과 직렬화하는 용도.
     *
     * @return 잠근 상품 id, 행이 없으면 빈 값
     */
    @Query(value = "SELECT id FROM product_schema.products WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<UUID> lockById(@Param("id") UUID id);

    /**
     * 조합에서 최저가·재고유무 캐시를 다시 계산해 상품 행에 쓴다(DDL 정의 그대로 한 문장).
     *
     * <ul>
     *   <li>{@code min_price} = 판매가능하고 가용재고가 있는 조합의 MIN(COALESCE(승자 할인 최종가, 조합 가격)), 없으면 NULL
     *   <li>{@code has_stock} = 판매가능하고 가용재고({@code stock - reserved_stock}) &gt; 0인 조합이 있는지
     * </ul>
     * 파생 캐시 갱신이라 version은 올리지 않고(판매자 수정 폼 version 유지), updated_at은 갱신한다(검색 재색인 대상 선정 기준).
     *
     * @return 갱신된 행 수(0 또는 1)
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE product_schema.products p
               SET min_price = (SELECT MIN(COALESCE(w.final_price, c.price))
                                  FROM product_schema.product_option_combinations c
                                  LEFT JOIN product_schema.combination_winning_discounts w ON w.combination_id = c.id
                                 WHERE c.product_id = p.id
                                   AND c.is_available
                                   AND c.stock - c.reserved_stock > 0),
                   has_stock = EXISTS (SELECT 1
                                         FROM product_schema.product_option_combinations c
                                        WHERE c.product_id = p.id
                                          AND c.is_available
                                          AND c.stock - c.reserved_stock > 0),
                   updated_at = :now
             WHERE p.id = :id
            """, nativeQuery = true)
    int refreshStockPriceCache(@Param("id") UUID id, @Param("now") Instant now);
}
