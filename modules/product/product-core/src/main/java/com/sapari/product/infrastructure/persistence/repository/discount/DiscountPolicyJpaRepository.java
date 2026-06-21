package com.sapari.product.infrastructure.persistence.repository.discount;

import com.sapari.product.infrastructure.persistence.entity.discount.DiscountPolicyEntity;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@code discount_policies} Spring Data JPA 어댑터. 활성 정책 조회만 파생 쿼리로 제공.
 */
public interface DiscountPolicyJpaRepository extends JpaRepository<DiscountPolicyEntity, UUID> {

    List<DiscountPolicyEntity> findByIsActiveTrue();
}
