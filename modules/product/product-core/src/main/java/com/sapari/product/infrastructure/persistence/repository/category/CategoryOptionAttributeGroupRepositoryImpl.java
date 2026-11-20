package com.sapari.product.infrastructure.persistence.repository.category;

import com.sapari.product.domain.model.category.CategoryOptionAttributeGroup;
import com.sapari.product.domain.repository.category.CategoryOptionAttributeGroupRepository;
import com.sapari.product.infrastructure.persistence.mapper.category.CategoryOptionAttributeGroupMapper;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * {@link CategoryOptionAttributeGroupRepository} 영속 어댑터. {@code save}는 upsert (도메인 {@code id}가 null이면 INSERT, 있으면 로드 후
 * mutator 갱신).
 */
@Repository
@RequiredArgsConstructor
public class CategoryOptionAttributeGroupRepositoryImpl
        implements CategoryOptionAttributeGroupRepository {

    private final CategoryOptionAttributeGroupJpaRepository jpaRepository;
    private final CategoryOptionAttributeGroupMapper mapper;

    @Override
    public CategoryOptionAttributeGroup save(CategoryOptionAttributeGroup domain) {
        if (domain.id() == null) {
            var saved = jpaRepository.save(mapper.toEntity(domain));
            return mapper.toDomain(saved);
        }
        var existing = jpaRepository.findById(domain.id())
                .orElseThrow(() -> new EntityNotFoundException("카테고리 속성 매핑을 찾을 수 없습니다: " + domain.id()));
        mapper.updateEntityFromDomain(existing, domain);
        return mapper.toDomain(jpaRepository.save(existing));
    }

    @Override
    public Optional<CategoryOptionAttributeGroup> findById(UUID id) {
        return jpaRepository.findById(id)
                .map(mapper::toDomain);
    }

    @Override
    public List<CategoryOptionAttributeGroup> findByCategoryId(Long categoryId) {
        return jpaRepository.findByCategoryId(categoryId)
                .stream()
                .map(mapper::toDomain)
                .toList();
    }
}
