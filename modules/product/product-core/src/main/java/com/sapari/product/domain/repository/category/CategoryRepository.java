package com.sapari.product.domain.repository.category;

import com.sapari.product.domain.model.category.Category;
import java.util.List;
import java.util.Optional;

/**
 * 카테고리 애그리거트의 영속 포트.
 *
 * <p>{@link #save}는 upsert(도메인 {@code id}가 null이면 INSERT, 있으면 갱신).
 * {@link #findByParentId}는 특정 부모의 직계 자식 목록(트리 1단계)을 반환한다.
 */
public interface CategoryRepository {

    Category save(Category category);

    Optional<Category> findById(Long id);

    List<Category> findByParentId(Long parentId);
}
