package com.sapari.product.infrastructure.persistence.mapper.category;

import com.sapari.product.infrastructure.persistence.entity.category.CategoryEntity;

import com.sapari.product.domain.model.category.Category;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/**
 * {@link Category} 도메인 ↔ {@link CategoryEntity} 변환 (MapStruct). 평면 필드는 자동 매핑하고, 갱신 경로({@code updateEntityFromDomain})는
 * 엔티티 mutator를 호출하는 default 메서드로 처리한다(빌더는 신규 생성용이라 in-place 갱신 불가).
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface CategoryMapper {

    Category toDomain(CategoryEntity entity);

    CategoryEntity toEntity(Category category);

    default void updateEntityFromDomain(
            CategoryEntity entity, Category category) {
        entity.updateParentId(category.parentId());
        entity.updatePath(category.path());
        entity.updateName(category.name());
        entity.updateDepth(category.depth());
        entity.updateSortOrder(category.sortOrder());
        entity.updateIsActive(category.isActive());
    }
}
