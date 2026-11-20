package com.sapari.product.infrastructure.persistence.mapper.category;

import com.sapari.product.infrastructure.persistence.entity.category.CategoryOptionAttributeGroupEntity;

import com.sapari.product.domain.model.category.CategoryOptionAttributeGroup;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/**
 * {@link CategoryOptionAttributeGroup} 도메인 ↔ {@link CategoryOptionAttributeGroupEntity} 변환 (MapStruct). 평면 필드 자동 매핑 +
 * 갱신은 엔티티 mutator를 호출하는 default 메서드.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface CategoryOptionAttributeGroupMapper {

    CategoryOptionAttributeGroup toDomain(
            CategoryOptionAttributeGroupEntity entity);

    CategoryOptionAttributeGroupEntity toEntity(
            CategoryOptionAttributeGroup domain);

    default void updateEntityFromDomain(
            CategoryOptionAttributeGroupEntity entity,
            CategoryOptionAttributeGroup domain) {
        entity.updateCategoryId(domain.categoryId());
        entity.updateAttributeGroupId(domain.attributeGroupId());
        entity.updateSortOrder(domain.sortOrder());
    }
}
