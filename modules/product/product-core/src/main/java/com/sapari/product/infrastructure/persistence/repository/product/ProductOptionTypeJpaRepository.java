package com.sapari.product.infrastructure.persistence.repository.product;

import com.sapari.product.infrastructure.persistence.entity.product.option.ProductOptionTypeEntity;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 옵션 타입(product_option_types) 리포지토리. productId 기준 조회·일괄삭제.
 */
public interface ProductOptionTypeJpaRepository extends JpaRepository<ProductOptionTypeEntity, UUID> {
    List<ProductOptionTypeEntity> findByProductId(UUID productId);

    void deleteByProductId(UUID productId);
}
