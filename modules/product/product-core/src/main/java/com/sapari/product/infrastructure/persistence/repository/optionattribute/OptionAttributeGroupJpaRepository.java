package com.sapari.product.infrastructure.persistence.repository.optionattribute;

import com.sapari.product.infrastructure.persistence.entity.optionattribute.OptionAttributeGroupEntity;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * {@code option_attribute_groups} JPA 리포지토리. 판매자별/시스템(공용) 그룹 파생 쿼리 제공.
 */
@Repository
public interface OptionAttributeGroupJpaRepository
        extends JpaRepository<OptionAttributeGroupEntity, UUID> {

    List<OptionAttributeGroupEntity> findBySellerId(UUID sellerId);

    List<OptionAttributeGroupEntity> findByIsSystemTrue();
}
