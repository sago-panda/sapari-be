package com.sapari.product.infrastructure.persistence.repository.category;

import com.sapari.product.domain.model.category.Category;
import com.sapari.product.domain.repository.category.CategoryRepository;
import com.sapari.product.infrastructure.persistence.mapper.category.CategoryMapper;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * {@link CategoryRepository} 영속 어댑터. {@code save}는 upsert — 도메인 {@code id}가 null이면 INSERT, 있으면 기존 엔티티를 로드해 mutator로 갱신
 * 후 저장한다(대상이 없으면 {@code EntityNotFoundException}).
 */
@Repository
@RequiredArgsConstructor
public class CategoryRepositoryImpl implements CategoryRepository {

    private final CategoryJpaRepository categoryJpaRepository;
    private final CategoryMapper categoryMapper;

    @Override
    public Category save(Category category) {
        if (category.id() == null) {
            var saved = categoryJpaRepository.save(categoryMapper.toEntity(category));
            return categoryMapper.toDomain(saved);
        }
        var existing = categoryJpaRepository.findById(category.id())
                .orElseThrow(() -> new EntityNotFoundException("카테고리를 찾을 수 없습니다: " + category.id()));
        categoryMapper.updateEntityFromDomain(existing, category);
        return categoryMapper.toDomain(categoryJpaRepository.save(existing));
    }

    @Override
    public Optional<Category> findById(Long id) {
        return categoryJpaRepository.findById(id)
                .map(categoryMapper::toDomain);
    }

    @Override
    public List<Category> findByParentId(Long parentId) {
        return categoryJpaRepository.findByParentId(parentId)
                .stream()
                .map(categoryMapper::toDomain)
                .toList();
    }
}
