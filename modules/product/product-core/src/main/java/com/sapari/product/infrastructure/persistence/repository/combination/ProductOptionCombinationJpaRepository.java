package com.sapari.product.infrastructure.persistence.repository.combination;

import com.sapari.product.infrastructure.persistence.entity.combination.ProductOptionCombinationEntity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@code product_option_combinations} Spring Data JPA 어댑터. 파생 쿼리로 상품별·SKU 조회를 제공한다.
 */
public interface ProductOptionCombinationJpaRepository
        extends JpaRepository<ProductOptionCombinationEntity, UUID> {

    List<ProductOptionCombinationEntity> findByProductId(UUID productId);

    Optional<ProductOptionCombinationEntity> findByProductIdAndSku(UUID productId, String sku);
}
