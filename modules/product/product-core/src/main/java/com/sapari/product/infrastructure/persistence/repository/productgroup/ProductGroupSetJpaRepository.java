package com.sapari.product.infrastructure.persistence.repository.productgroup;

import com.sapari.product.infrastructure.persistence.entity.productgroup.ProductGroupSetEntity;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * {@code product_group_sets} JPA 리포지토리 (판매자별 그룹 조회).
 */
@Repository
public interface ProductGroupSetJpaRepository extends JpaRepository<ProductGroupSetEntity, UUID> {

    List<ProductGroupSetEntity> findBySellerId(UUID sellerId);
}
