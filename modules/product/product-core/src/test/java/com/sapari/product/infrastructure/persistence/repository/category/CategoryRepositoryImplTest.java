package com.sapari.product.infrastructure.persistence.repository.category;

import static org.assertj.core.api.Assertions.assertThat;

import com.sapari.product.domain.model.category.Category;
import com.sapari.product.domain.repository.category.CategoryRepository;
import com.sapari.product.support.AbstractRepositoryIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link CategoryRepositoryImpl} 통합 테스트. id는 bigint IDENTITY(DB 생성), path는 ltree 컬럼.
 */
@DisplayName("CategoryRepositoryImpl 통합 테스트")
class CategoryRepositoryImplTest extends AbstractRepositoryIntegrationTest {

    @Autowired
    CategoryRepository repository;

    @PersistenceContext
    EntityManager em;

    private Category reload(Long id) {
        em.flush();
        em.clear();
        return repository.findById(id)
                .orElseThrow();
    }

    @Nested
    @DisplayName("신규 저장(INSERT)")
    class SaveNew {

        @Test
        @DisplayName("IDENTITY id를 DB가 채우고, 스칼라(ltree path 포함)를 그대로 왕복 저장한다")
        void assigns_identity_id_and_round_trips() {
            Category saved = repository.save(Category.create(null, "1042", "전자", (short) 1, 0, true));

            assertThat(saved.id()).isNotNull();   // bigint IDENTITY 할당
            Category reloaded = reload(saved.id());
            assertThat(reloaded.parentId()).isNull();
            assertThat(reloaded.path()).isEqualTo("1042");   // ltree round-trip
            assertThat(reloaded.name()).isEqualTo("전자");
            assertThat(reloaded.depth()).isEqualTo((short) 1);
            assertThat(reloaded.sortOrder()).isZero();
            assertThat(reloaded.isActive()).isTrue();
            assertThat(reloaded.createdAt()).isNotNull();
            assertThat(reloaded.updatedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("조회")
    class Finders {

        @Test
        @DisplayName("findByParentId는 직계 자식만 반환한다")
        void findByParentId_returns_direct_children() {
            Category parent = repository.save(Category.create(null, "1042", "전자", (short) 1, 0, true));
            em.flush();
            em.clear();
            Category child = repository.save(
                    Category.create(parent.id(), "1042.3851", "노트북", (short) 2, 0, true));
            em.flush();
            em.clear();

            assertThat(repository.findByParentId(parent.id())).extracting(Category::id)
                    .containsExactly(child.id());
            assertThat(repository.findByParentId(999_999L)).isEmpty();
        }

        @Test
        @DisplayName("존재하지 않는 id는 Optional.empty")
        void findById_unknown_empty() {
            assertThat(repository.findById(999_999L)).isEmpty();
        }
    }

    @Nested
    @DisplayName("갱신(UPDATE)")
    class Update {

        @Test
        @DisplayName("이름을 갱신하고 created_at은 보존한다")
        void renames_and_keeps_createdAt() {
            Category saved = repository.save(Category.create(null, "1042", "전자", (short) 1, 0, true));
            em.flush();
            em.clear();
            Instant createdAt = repository.findById(saved.id())
                    .orElseThrow()
                    .createdAt();

            repository.save(repository.findById(saved.id())
                    .orElseThrow()
                    .toBuilder()
                    .name("가전")
                    .build());
            Category after = reload(saved.id());

            assertThat(after.name()).isEqualTo("가전");
            assertThat(after.createdAt()).isEqualTo(createdAt);
        }
    }
}
