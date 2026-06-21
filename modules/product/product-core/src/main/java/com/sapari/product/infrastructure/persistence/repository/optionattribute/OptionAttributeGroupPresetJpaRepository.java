package com.sapari.product.infrastructure.persistence.repository.optionattribute;

import com.sapari.product.infrastructure.persistence.entity.optionattribute.OptionAttributeGroupPresetEntity;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * {@code option_attribute_group_presets} JPA 리포지토리. 그룹 단위 정렬 조회 + 교체 저장용 삭제 제공.
 */
@Repository
public interface OptionAttributeGroupPresetJpaRepository
        extends JpaRepository<OptionAttributeGroupPresetEntity, UUID> {

    List<OptionAttributeGroupPresetEntity> findByAttributeGroupIdOrderBySortOrderAsc(UUID attributeGroupId);

    void deleteByAttributeGroupId(UUID attributeGroupId);
}
