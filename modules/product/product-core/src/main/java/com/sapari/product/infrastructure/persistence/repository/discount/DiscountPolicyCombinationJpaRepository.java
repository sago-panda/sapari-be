package com.sapari.product.infrastructure.persistence.repository.discount;

import com.sapari.product.infrastructure.persistence.entity.discount.DiscountPolicyCombinationEntity;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@code discount_policy_combinations}(정책↔조합, combination-level — product-level보다 우선) JPA 어댑터.
 */
public interface DiscountPolicyCombinationJpaRepository
        extends JpaRepository<DiscountPolicyCombinationEntity, UUID> {

    List<DiscountPolicyCombinationEntity> findByDiscountPolicyId(UUID discountPolicyId);

    void deleteByDiscountPolicyId(UUID discountPolicyId);
}
