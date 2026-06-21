package com.sapari.product.infrastructure.persistence.repository.faq;

import com.sapari.product.infrastructure.persistence.entity.faq.ProductFaqEntity;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@link ProductFaqEntity} Spring Data 어댑터. 상품별 문의 조회 파생 쿼리 제공.
 */
public interface ProductFaqJpaRepository extends JpaRepository<ProductFaqEntity, UUID> {
    List<ProductFaqEntity> findByProductId(UUID productId);
}
