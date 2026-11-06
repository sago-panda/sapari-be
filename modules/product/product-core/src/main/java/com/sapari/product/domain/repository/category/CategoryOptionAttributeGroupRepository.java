package com.sapari.product.domain.repository.category;

import com.sapari.product.domain.model.category.CategoryOptionAttributeGroup;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 카테고리-옵션 속성 그룹 매핑의 영속 포트. {@link #save}는 upsert, {@link #findByCategoryId}는 해당 카테고리의 추천 속성 그룹 목록을 반환한다.
 */
public interface CategoryOptionAttributeGroupRepository {

    CategoryOptionAttributeGroup save(CategoryOptionAttributeGroup categoryOptionAttributeGroup);

    Optional<CategoryOptionAttributeGroup> findById(UUID id);

    List<CategoryOptionAttributeGroup> findByCategoryId(Long categoryId);
}
