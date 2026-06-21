package com.sapari.product.infrastructure.persistence.repository.discount;

import com.sapari.product.infrastructure.persistence.entity.discount.CombinationWinningDiscountEntity;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@code combination_winning_discounts}(조합당 승자 할인 파생 캐시, PK=combination_id) JPA 어댑터.
 */
public interface CombinationWinningDiscountJpaRepository
        extends JpaRepository<CombinationWinningDiscountEntity, UUID> {

    List<CombinationWinningDiscountEntity> findByPolicyId(UUID policyId);
}
