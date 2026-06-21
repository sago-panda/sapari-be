package com.sapari.product.infrastructure.persistence.repository.productgroup;

import static com.sapari.product.support.ProductFixtures.SELLER_A;
import static com.sapari.product.support.ProductFixtures.SELLER_B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.sapari.product.domain.model.productgroup.ProductGroupItemRef;
import com.sapari.product.domain.model.productgroup.ProductGroupSet;
import com.sapari.product.domain.repository.productgroup.ProductGroupSetRepository;
import com.sapari.product.support.AbstractRepositoryIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link ProductGroupSetRepositoryImpl} 통합 테스트. 그룹 + 자식(items) upsert/교체, 판매자 필터.
 */
@DisplayName("ProductGroupSetRepositoryImpl 통합 테스트")
class ProductGroupSetRepositoryImplTest extends AbstractRepositoryIntegrationTest {

    @Autowired
    ProductGroupSetRepository repository;

    @PersistenceContext
    EntityManager em;

    @Nested
    @DisplayName("저장·조회")
    class SaveAndFind {

        @Test
        @DisplayName("아이템과 함께 저장하고 그대로 왕복 조회한다")
        void save_round_trips_with_items() {
            UUID product1 = UUID.randomUUID();
            UUID product2 = UUID.randomUUID();
            ProductGroupSet saved = repository.save(ProductGroupSet.create(SELLER_A, "여름 기획전",
                    List.of(ProductGroupItemRef.of(product1, 1), ProductGroupItemRef.of(product2, 2))));
            em.flush();
            em.clear();

            ProductGroupSet reloaded = repository.findById(saved.id())
                    .orElseThrow();
            assertThat(reloaded.sellerId()).isEqualTo(SELLER_A);
            assertThat(reloaded.groupName()).isEqualTo("여름 기획전");
            assertThat(reloaded.items()).extracting(ProductGroupItemRef::productId)
                    .containsExactlyInAnyOrder(product1, product2);
        }

        @Test
        @DisplayName("findBySellerId는 해당 판매자 그룹만 반환한다")
        void findBySellerId_filters() {
            repository.save(ProductGroupSet.create(SELLER_A, "A1", List.of()));
            repository.save(ProductGroupSet.create(SELLER_A, "A2", List.of()));
            repository.save(ProductGroupSet.create(SELLER_B, "B1", List.of()));
            em.flush();
            em.clear();

            assertThat(repository.findBySellerId(SELLER_A)).hasSize(2)
                    .allSatisfy(g -> assertThat(g.sellerId()).isEqualTo(SELLER_A));
            assertThat(repository.findBySellerId(UUID.randomUUID())).isEmpty();
        }

        @Test
        @DisplayName("존재하지 않는 id는 Optional.empty")
        void findById_unknown_empty() {
            assertThat(repository.findById(UUID.randomUUID())).isEmpty();
        }
    }

    @Nested
    @DisplayName("갱신(UPDATE)")
    class Update {

        @Test
        @DisplayName("아이템을 다른 집합으로 전체 교체한다")
        void replaces_items_with_different_set() {
            UUID product1 = UUID.randomUUID();
            UUID product2 = UUID.randomUUID();
            ProductGroupSet saved = repository.save(ProductGroupSet.create(SELLER_A, "그룹",
                    List.of(ProductGroupItemRef.of(product1, 1), ProductGroupItemRef.of(product2, 2))));
            em.flush();
            em.clear();

            UUID product3 = UUID.randomUUID();
            repository.save(repository.findById(saved.id())
                    .orElseThrow()
                    .toBuilder()
                    .items(List.of(ProductGroupItemRef.of(product3, 1)))
                    .build());
            em.flush();
            em.clear();

            ProductGroupSet after = repository.findById(saved.id())
                    .orElseThrow();
            assertThat(after.items()).extracting(ProductGroupItemRef::productId)
                    .containsExactly(product3);
        }
    }

    @Nested
    @DisplayName("자식 교체 회귀(unique 충돌 방지)")
    class ChildReplaceRegression {

        @Test
        @DisplayName("아이템 교체 시 동일 (group_set_id, product_id) 재삽입에도 unique 충돌 없이 저장된다")
        void replaceItems_reusingSameProduct_noUniqueViolation() {
            UUID product1 = UUID.randomUUID();
            UUID product2 = UUID.randomUUID();
            ProductGroupSet saved = repository.save(ProductGroupSet.create(SELLER_A, "그룹",
                    List.of(ProductGroupItemRef.of(product1, 1), ProductGroupItemRef.of(product2, 2))));
            em.flush();
            em.clear();

            // product1은 그대로 재포함(같은 unique 키 재삽입), product2 제거 + product3 추가
            UUID product3 = UUID.randomUUID();
            ProductGroupSet updated = repository.findById(saved.id())
                    .orElseThrow()
                    .toBuilder()
                    .items(List.of(ProductGroupItemRef.of(product1, 1), ProductGroupItemRef.of(product3, 2)))
                    .build();

            assertThatNoException().isThrownBy(() -> repository.save(updated));
            em.flush();
            em.clear();

            ProductGroupSet after = repository.findById(saved.id())
                    .orElseThrow();
            assertThat(after.items()).extracting(ProductGroupItemRef::productId)
                    .containsExactlyInAnyOrder(product1, product3);
        }
    }
}
