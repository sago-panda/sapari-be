package com.sapari.product.infrastructure.persistence.repository.product;

import com.sapari.product.infrastructure.persistence.entity.product.ProductTagEntity;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 상품 태그(product_tags) 리포지토리. 자식 replace를 위해 productId 기준 조회·일괄삭제를 제공한다.
 */
public interface ProductTagJpaRepository extends JpaRepository<ProductTagEntity, UUID> {
    List<ProductTagEntity> findByProductId(UUID productId);

    void deleteByProductId(UUID productId);
}
