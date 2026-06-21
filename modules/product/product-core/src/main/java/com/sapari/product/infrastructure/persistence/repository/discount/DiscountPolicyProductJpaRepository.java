package com.sapari.product.infrastructure.persistence.repository.discount;

import com.sapari.product.infrastructure.persistence.entity.discount.DiscountPolicyProductEntity;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@code discount_policy_products}(정책↔상품, product-level) JPA 어댑터. 정책 저장 시 교체(delete+재삽입)에 사용.
 */
public interface DiscountPolicyProductJpaRepository
        extends JpaRepository<DiscountPolicyProductEntity, UUID> {

    List<DiscountPolicyProductEntity> findByDiscountPolicyId(UUID discountPolicyId);

    void deleteByDiscountPolicyId(UUID discountPolicyId);
}
