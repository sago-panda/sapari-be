package com.sapari.product.infrastructure.persistence.repository.category;

import static org.assertj.core.api.Assertions.assertThat;

import com.sapari.product.domain.model.category.CategoryOptionAttributeGroup;
import com.sapari.product.domain.repository.category.CategoryOptionAttributeGroupRepository;
import com.sapari.product.support.AbstractRepositoryIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link CategoryOptionAttributeGroupRepositoryImpl} 통합 테스트(카테고리-옵션 속성 그룹 매핑).
 */
@DisplayName("CategoryOptionAttributeGroupRepositoryImpl 통합 테스트")
class CategoryOptionAttributeGroupRepositoryImplTest extends AbstractRepositoryIntegrationTest {

    @Autowired
    CategoryOptionAttributeGroupRepository repository;

    @PersistenceContext
    EntityManager em;

    @Test
    @DisplayName("저장 후 스칼라를 그대로 왕복 조회한다")
    void save_round_trips() {
        UUID attributeGroupId = UUID.randomUUID();

        CategoryOptionAttributeGroup saved =
                repository.save(CategoryOptionAttributeGroup.create(7000L, attributeGroupId, 3));
        assertThat(saved.id()).isNotNull();
        em.flush();
        em.clear();

        CategoryOptionAttributeGroup reloaded = repository.findById(saved.id())
                .orElseThrow();
        assertThat(reloaded.categoryId()).isEqualTo(7000L);
        assertThat(reloaded.attributeGroupId()).isEqualTo(attributeGroupId);
        assertThat(reloaded.sortOrder()).isEqualTo(3);
        assertThat(reloaded.createdAt()).isNotNull();
    }

    @Test
    @DisplayName("findByCategoryId는 해당 카테고리 매핑만 반환한다")
    void findByCategoryId_filters() {
        repository.save(CategoryOptionAttributeGroup.create(100L, UUID.randomUUID(), 0));
        repository.save(CategoryOptionAttributeGroup.create(100L, UUID.randomUUID(), 1));
        repository.save(CategoryOptionAttributeGroup.create(200L, UUID.randomUUID(), 0));
        em.flush();
        em.clear();

        assertThat(repository.findByCategoryId(100L)).hasSize(2)
                .allSatisfy(m -> assertThat(m.categoryId()).isEqualTo(100L));
        assertThat(repository.findByCategoryId(999L)).isEmpty();
    }

    @Test
    @DisplayName("존재하지 않는 id는 Optional.empty")
    void findById_unknown_empty() {
        assertThat(repository.findById(UUID.randomUUID())).isEmpty();
    }
}
