package com.sapari.product.infrastructure.persistence.repository.combination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sapari.product.domain.exception.ProductDomainException;
import com.sapari.product.domain.exception.ProductErrorCode;
import com.sapari.product.domain.model.combination.CombinationKey;
import com.sapari.product.domain.model.combination.ProductOptionCombination;
import com.sapari.product.domain.model.combination.Sku;
import com.sapari.product.domain.model.combination.Stock;
import com.sapari.product.domain.repository.combination.ProductOptionCombinationRepository;
import com.sapari.product.support.AbstractRepositoryIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link ProductOptionCombinationRepositoryImpl} 통합 테스트. 루트(VO 포함) + 자식(optionValueIds) upsert/교체, 파생 조회.
 */
@DisplayName("ProductOptionCombinationRepositoryImpl 통합 테스트")
class ProductOptionCombinationRepositoryImplTest extends AbstractRepositoryIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    ProductOptionCombinationRepository repository;

    @PersistenceContext
    EntityManager em;

    private ProductOptionCombination reload(UUID id) {
        em.flush();
        em.clear();
        return repository.findById(id)
                .orElseThrow();
    }

    private ProductOptionCombination newCombo(UUID productId, String key, String sku, int price) {
        return ProductOptionCombination.create(productId, CombinationKey.of(key), Sku.of(sku),
                null, price, Stock.of(10, 0), List.of(), T0);
    }

    @Nested
    @DisplayName("저장·조회")
    class SaveAndFind {

        @Test
        @DisplayName("VO·옵션값까지 전 필드를 그대로 왕복 저장한다")
        void save_round_trips_all_fields() {
            UUID productId = UUID.randomUUID();
            UUID value1 = UUID.randomUUID();
            UUID value2 = UUID.randomUUID();
            ProductOptionCombination saved = repository.save(ProductOptionCombination.create(
                    productId, CombinationKey.of("3:7"), Sku.of("SKU-A"), 12_000, 9_900,
                    Stock.of(50, 5), List.of(value1, value2), T0));

            ProductOptionCombination r = reload(saved.id());
            assertThat(r.id()).isNotNull();
            assertThat(r.productId()).isEqualTo(productId);
            assertThat(r.combinationKey()
                    .value()).isEqualTo("3:7");
            assertThat(r.sku()
                    .value()).isEqualTo("SKU-A");
            assertThat(r.originalPrice()).isEqualTo(12_000);
            assertThat(r.price()).isEqualTo(9_900);
            assertThat(r.stock()
                    .stock()).isEqualTo(50);
            assertThat(r.stock()
                    .reservedStock()).isEqualTo(5);
            assertThat(r.availableStock()).isEqualTo(45);
            assertThat(r.isAvailable()).isTrue();
            assertThat(r.optionValueIds()).containsExactlyInAnyOrder(value1, value2);
            assertThat(r.createdAt()).isNotNull();
            assertThat(r.updatedAt()).isNotNull();
        }

        @Test
        @DisplayName("null Sku·null originalPrice·빈 옵션값을 그대로 왕복한다")
        void nullable_fields_round_trip() {
            ProductOptionCombination saved = repository.save(ProductOptionCombination.create(
                    UUID.randomUUID(), CombinationKey.of("1"), Sku.of(null), null, 1_000,
                    Stock.of(10, 0), List.of(), T0));

            ProductOptionCombination r = reload(saved.id());
            assertThat(r.sku()).isNull();
            assertThat(r.originalPrice()).isNull();
            assertThat(r.optionValueIds()).isEmpty();
        }

        @Test
        @DisplayName("findByProductId는 해당 상품 조합만 반환한다")
        void findByProductId_filters() {
            UUID productId = UUID.randomUUID();
            repository.save(newCombo(productId, "1", null, 1000));
            repository.save(newCombo(productId, "2", null, 2000));
            repository.save(newCombo(UUID.randomUUID(), "1", null, 3000));
            em.flush();
            em.clear();

            assertThat(repository.findByProductId(productId)).hasSize(2)
                    .allSatisfy(c -> assertThat(c.productId()).isEqualTo(productId));
            assertThat(repository.findByProductId(UUID.randomUUID())).isEmpty();
        }

        @Test
        @DisplayName("findByProductId는 조합별로 옵션값을 정확히 그룹핑한다(다건 조합·다값, 어긋남 없음)")
        void findByProductId_groups_values_per_combination() {
            UUID productId = UUID.randomUUID();
            UUID v1 = UUID.randomUUID();
            UUID v2 = UUID.randomUUID();
            UUID v3 = UUID.randomUUID();
            repository.save(ProductOptionCombination.create(
                    productId, CombinationKey.of("a"), Sku.of("A"), null, 1_000, Stock.of(10, 0), List.of(v1, v2), T0));
            repository.save(ProductOptionCombination.create(
                    productId, CombinationKey.of("b"), Sku.of("B"), null, 2_000, Stock.of(10, 0), List.of(v3), T0));
            em.flush();
            em.clear();

            List<ProductOptionCombination> found = repository.findByProductId(productId);
            assertThat(found).hasSize(2);
            assertThat(found).filteredOn(c -> c.combinationKey().value().equals("a"))
                    .singleElement().satisfies(c -> assertThat(c.optionValueIds()).containsExactlyInAnyOrder(v1, v2));
            assertThat(found).filteredOn(c -> c.combinationKey().value().equals("b"))
                    .singleElement().satisfies(c -> assertThat(c.optionValueIds()).containsExactly(v3));
        }

        @Test
        @DisplayName("findByProductIdAndSku는 상품 범위 내 SKU로 단건 조회한다")
        void findByProductIdAndSku() {
            UUID productId = UUID.randomUUID();
            repository.save(newCombo(productId, "1", "SKU-X", 1000));
            em.flush();
            em.clear();

            assertThat(repository.findByProductIdAndSku(productId, "SKU-X")).isPresent();
            assertThat(repository.findByProductIdAndSku(productId, "NOPE")).isEmpty();
        }

        @Test
        @DisplayName("존재하지 않는 id는 Optional.empty")
        void findById_unknown_empty() {
            assertThat(repository.findById(UUID.randomUUID())).isEmpty();
        }
    }

    @Nested
    @DisplayName("일괄 저장(saveAll)")
    class SaveAll {

        @Test
        @DisplayName("신규 조합들을 옵션값 매핑까지 일괄 저장하고 그대로 조립해 돌려준다")
        void saveAll_persists_combinations_with_values() {
            UUID productId = UUID.randomUUID();
            UUID v1 = UUID.randomUUID();
            UUID v2 = UUID.randomUUID();
            ProductOptionCombination c1 = ProductOptionCombination.create(
                    productId, CombinationKey.of("1"), Sku.of("SKU-1"), null, 1_000, Stock.of(10, 0), List.of(v1), T0);
            ProductOptionCombination c2 = ProductOptionCombination.create(
                    productId, CombinationKey.of("2"), Sku.of("SKU-2"), null, 2_000, Stock.of(20, 0), List.of(v2), T0);

            List<ProductOptionCombination> saved = repository.saveAll(List.of(c1, c2));
            // 반환값: 재조회 없이 id가 채워지고 각 조합의 옵션값 매핑이 올바르게 유지된다(zip 정확성)
            assertThat(saved).hasSize(2).allSatisfy(c -> assertThat(c.id()).isNotNull());
            assertThat(saved).filteredOn(c -> c.combinationKey().value().equals("1"))
                    .singleElement().satisfies(c -> assertThat(c.optionValueIds()).containsExactly(v1));
            assertThat(saved).filteredOn(c -> c.combinationKey().value().equals("2"))
                    .singleElement().satisfies(c -> assertThat(c.optionValueIds()).containsExactly(v2));
            em.flush();
            em.clear();

            // 재조회 시에도 키↔옵션값 매핑이 어긋나지 않는다
            List<ProductOptionCombination> found = repository.findByProductId(productId);
            assertThat(found).hasSize(2);
            assertThat(found).filteredOn(c -> c.combinationKey().value().equals("1"))
                    .singleElement().satisfies(c -> assertThat(c.optionValueIds()).containsExactly(v1));
            assertThat(found).filteredOn(c -> c.combinationKey().value().equals("2"))
                    .singleElement().satisfies(c -> assertThat(c.optionValueIds()).containsExactly(v2));
        }

        @Test
        @DisplayName("빈 리스트면 아무것도 저장하지 않고 빈 리스트를 반환한다")
        void saveAll_empty_isNoop() {
            assertThat(repository.saveAll(List.of())).isEmpty();
        }

        @Test
        @DisplayName("id가 이미 있는 조합을 넘기면 거부한다 (신규 전용 precondition)")
        void saveAll_rejects_existing_combination() {
            ProductOptionCombination existing = ProductOptionCombination.builder()
                    .id(UUID.randomUUID())
                    .productId(UUID.randomUUID())
                    .combinationKey(CombinationKey.of("1"))
                    .price(1_000)
                    .stock(Stock.of(1, 0))
                    .isAvailable(true)
                    .optionValueIds(List.of())
                    .createdAt(T0)
                    .updatedAt(T0)
                    .build();

            // 도메인 예외라 @Repository 변환 없이 그대로 전파 (코드=동작=로그 일치)
            assertThatThrownBy(() -> repository.saveAll(List.of(existing)))
                    .isInstanceOf(ProductDomainException.class)
                    .satisfies(ex -> assertThat(((ProductDomainException) ex).getErrorCode())
                            .isEqualTo(ProductErrorCode.INTERNAL_PRODUCT_ERROR));
        }
    }

    @Nested
    @DisplayName("갱신(UPDATE)")
    class Update {

        @Test
        @DisplayName("루트 필드를 갱신하고 옵션값을 다른 집합으로 교체한다")
        void updates_root_and_replaces_values() {
            UUID productId = UUID.randomUUID();
            UUID value1 = UUID.randomUUID();
            UUID value2 = UUID.randomUUID();
            ProductOptionCombination saved = repository.save(ProductOptionCombination.create(
                    productId, CombinationKey.of("1:2"), Sku.of("SKU-1"), null, 10_000,
                    Stock.of(100, 0), List.of(value1, value2), T0));
            em.flush();
            em.clear();

            UUID value3 = UUID.randomUUID();
            repository.save(repository.findById(saved.id())
                    .orElseThrow()
                    .toBuilder()
                    .price(8_000)
                    .stock(Stock.of(80, 10))
                    .isAvailable(false)
                    .optionValueIds(List.of(value2, value3))
                    .build());

            ProductOptionCombination r = reload(saved.id());
            assertThat(r.price()).isEqualTo(8_000);
            assertThat(r.stock()
                    .stock()).isEqualTo(80);
            assertThat(r.stock()
                    .reservedStock()).isEqualTo(10);
            assertThat(r.isAvailable()).isFalse();
            assertThat(r.optionValueIds()).containsExactlyInAnyOrder(value2, value3);
        }
    }

    @Nested
    @DisplayName("자식 교체 회귀(unique 충돌 방지)")
    class ChildReplaceRegression {

        @Test
        @DisplayName("옵션값 교체 시 동일 (combination_id, option_value_id) 재삽입에도 unique 충돌 없이 저장된다")
        void replaceValues_reusingSameValue_noUniqueViolation() {
            UUID value1 = UUID.randomUUID();
            UUID value2 = UUID.randomUUID();
            ProductOptionCombination saved = repository.save(ProductOptionCombination.create(
                    UUID.randomUUID(), CombinationKey.of("1:2"), Sku.of("SKU-1"),
                    null, 10_000, Stock.of(100, 0), List.of(value1, value2), T0));
            em.flush();
            em.clear();

            UUID value3 = UUID.randomUUID();
            ProductOptionCombination updated = repository.findById(saved.id())
                    .orElseThrow()
                    .toBuilder()
                    .optionValueIds(List.of(value1, value3))
                    .build();

            assertThatNoException().isThrownBy(() -> repository.save(updated));

            assertThat(reload(saved.id()).optionValueIds()).containsExactlyInAnyOrder(value1, value3);
        }
    }
}
