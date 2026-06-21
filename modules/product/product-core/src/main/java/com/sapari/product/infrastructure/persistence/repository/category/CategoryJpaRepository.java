package com.sapari.product.infrastructure.persistence.repository.category;

import com.sapari.product.infrastructure.persistence.entity.category.CategoryEntity;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * {@code categories} Spring Data JPA 리포지토리. {@code findByParentId}로 직계 자식을 조회한다.
 */
@Repository
public interface CategoryJpaRepository extends JpaRepository<CategoryEntity, Long> {

    List<CategoryEntity> findByParentId(Long parentId);
}
