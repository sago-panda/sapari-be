package com.sapari.product.infrastructure.persistence.repository.category;

import com.sapari.product.infrastructure.persistence.entity.category.CategoryOptionAttributeGroupEntity;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * {@code category_option_attribute_groups} JPA 리포지토리 (카테고리별 추천 속성 그룹 조회).
 */
@Repository
public interface CategoryOptionAttributeGroupJpaRepository
        extends JpaRepository<CategoryOptionAttributeGroupEntity, UUID> {

    List<CategoryOptionAttributeGroupEntity> findByCategoryId(Long categoryId);
}
