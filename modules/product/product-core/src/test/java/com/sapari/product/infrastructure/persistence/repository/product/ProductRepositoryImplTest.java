package com.sapari.product.infrastructure.persistence.repository.product;

import static com.sapari.product.support.ProductFixtures.CATEGORY_1;
import static com.sapari.product.support.ProductFixtures.CATEGORY_2;
import static com.sapari.product.support.ProductFixtures.SELLER_A;
import static com.sapari.product.support.ProductFixtures.SELLER_B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sapari.product.domain.model.product.ImageRole;
import com.sapari.product.domain.model.product.Product;
import com.sapari.product.domain.model.product.ProductImageRef;
import com.sapari.product.domain.model.product.ProductOptionTypeModel;
import com.sapari.product.domain.model.product.ProductOptionValueModel;
import com.sapari.product.domain.repository.product.ProductRepository;
import com.sapari.product.support.AbstractRepositoryIntegrationTest;
import com.sapari.product.support.ProductFixtures;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.jpa.JpaObjectRetrievalFailureException;

/**
 * {@link ProductRepositoryImpl} 통합 테스트. 실제 Postgres(Testcontainers) + 실제 마이그레이션 스키마에서 루트 upsert와 자식(태그/이미지/옵션
 * 타입·값/config) replace, 매퍼 라운드트립, 파생 조회를 검증한다.
 *
 * <p>입력은 {@link ProductFixtures}가 FixtureMonkey로 무작위 생성하며, 각 테스트는 저장→{@code flush+clear}→재조회한
 * 결과가 <b>입력과 동일한지(round-trip fidelity)</b>를 확인해, 하드코딩 상수가 아니라 임의의 유효 데이터에 대해 영속·매핑이 충실한지 검증한다.
 */
@DisplayName("ProductRepositoryImpl 통합 테스트")
class ProductRepositoryImplTest extends AbstractRepositoryIntegrationTest {

    @Autowired
    ProductRepository productRepository;
    @Autowired
    ProductOptionTypeJpaRepository optionTypeJpaRepository;
    @Autowired
    ProductOptionValueJpaRepository optionValueJpaRepository;

    @PersistenceContext
    EntityManager em;

    /**
     * 저장 후 영속성 컨텍스트를 비우고 DB에서 다시 읽어 라운드트립을 강제한다.
     */
    private Product saveAndReload(Product product) {
        Product saved = productRepository.save(product);
        em.flush();
        em.clear();
        return productRepository.findById(saved.id())
                .orElseThrow();
    }

    @Nested
    @DisplayName("신규 저장(INSERT)")
    class SaveNew {

        @Test
        @DisplayName("id·감사 시각을 채우고, FixtureMonkey 무작위 스칼라를 그대로 왕복 저장한다")
        void persists_scalar_fields() {
            Product input = ProductFixtures.full(SELLER_A, CATEGORY_1);

            Product reloaded = saveAndReload(input);

            assertThat(reloaded.id()).isNotNull();
            assertThat(reloaded.createdAt()).isNotNull();
            assertThat(reloaded.updatedAt()).isNotNull();
            // FixtureMonkey가 채운 임의 값이 손실 없이 왕복되는지(영속 충실도)
            assertThat(reloaded.sellerId()).isEqualTo(input.sellerId());
            assertThat(reloaded.categoryId()).isEqualTo(input.categoryId());
            assertThat(reloaded.name()).isEqualTo(input.name());
            assertThat(reloaded.description()).isEqualTo(input.description());
            assertThat(reloaded.basePrice()).isEqualTo(input.basePrice());
            assertThat(reloaded.status()).isEqualTo(input.status());
            assertThat(reloaded.optionModel()).isEqualTo(input.optionModel());
            assertThat(reloaded.shippingPolicyId()).isEqualTo(input.shippingPolicyId());
            assertThat(reloaded.additionalShippingFee()).isEqualTo(input.additionalShippingFee());
            assertThat(reloaded.minPrice()).isEqualTo(input.minPrice());
            assertThat(reloaded.hasStock()).isEqualTo(input.hasStock());
            assertThat(reloaded.purchaseCount()).isEqualTo(input.purchaseCount());
            assertThat(reloaded.reviewCount()).isEqualTo(input.reviewCount());
            assertThat(reloaded.viewCount()).isEqualTo(input.viewCount());
            assertThat(reloaded.wishlistCount()).isEqualTo(input.wishlistCount());
            assertThat(reloaded.rejectionReason()).isEqualTo(input.rejectionReason());
            assertThat(reloaded.avgRating()).isEqualByComparingTo(input.avgRating());  // numeric(3,1)
            assertThat(reloaded.deletedAt()).isNull();
        }

        @Test
        @DisplayName("태그·옵션 타입/값을 자식으로 저장하고 조립해 그대로 돌려준다")
        void persists_children() {
            Product input = ProductFixtures.full(SELLER_A, CATEGORY_1);

            Product reloaded = saveAndReload(input);

            assertThat(reloaded.tags()).containsExactlyInAnyOrderElementsOf(input.tags());
            assertThat(reloaded.optionTypes()).extracting(ProductOptionTypeModel::name)
                    .containsExactlyInAnyOrderElementsOf(
                            input.optionTypes()
                                    .stream()
                                    .map(ProductOptionTypeModel::name)
                                    .toList());
            // 각 타입의 값 집합도 동일하게 왕복되는지
            for (ProductOptionTypeModel inType : input.optionTypes()) {
                ProductOptionTypeModel reType = reloaded.optionTypes()
                        .stream()
                        .filter(t -> t.name()
                                .equals(inType.name()))
                        .findFirst()
                        .orElseThrow();
                assertThat(reType.values()).extracting(ProductOptionValueModel::value)
                        .containsExactlyInAnyOrderElementsOf(
                                inType.values()
                                        .stream()
                                        .map(ProductOptionValueModel::value)
                                        .toList());
            }
        }

        @Test
        @DisplayName("jsonb(옵션 config)를 왕복 저장한다")
        void persists_jsonb() {
            Product reloaded = saveAndReload(ProductFixtures.full(SELLER_A, CATEGORY_1));

            assertThat(reloaded.optionConfig()).isNotNull();
            // jsonb는 정규화(공백/키 순서)되므로 부분 비교
            assertThat(reloaded.optionConfig()
                    .surchargeRules()).contains("amount")
                    .contains("1000");
            assertThat(reloaded.optionConfig()
                    .exclusionRules()).isNull();
        }

        @Test
        @DisplayName("자식이 없으면 빈 컬렉션·null config로 돌려준다")
        void empty_children() {
            Product reloaded = saveAndReload(ProductFixtures.minimal(SELLER_A, CATEGORY_1));

            assertThat(reloaded.tags()).isEmpty();
            assertThat(reloaded.images()).isEmpty();
            assertThat(reloaded.optionTypes()).isEmpty();
            assertThat(reloaded.optionConfig()).isNull();
        }
    }

    @Nested
    @DisplayName("갱신(UPDATE)")
    class Update {

        @Test
        @DisplayName("루트 컬럼을 갱신하고 created_at은 보존, updated_at은 갱신한다")
        void updates_root_keeps_createdAt() {
            Product saved = productRepository.save(ProductFixtures.minimal(SELLER_A, CATEGORY_1));
            em.flush();
            em.clear();
            Instant createdAt = productRepository.findById(saved.id())
                    .orElseThrow()
                    .createdAt();

            productRepository.save(saved.approve(Instant.parse("2026-02-02T00:00:00Z")));
            em.flush();
            em.clear();

            Product after = productRepository.findById(saved.id())
                    .orElseThrow();
            assertThat(after.status()).isEqualTo(com.sapari.product.domain.model.product.ProductStatus.ON_SALE);
            assertThat(after.rejectionReason()).isNull();
            assertThat(after.createdAt()).isEqualTo(createdAt);
            assertThat(after.updatedAt()).isAfterOrEqualTo(createdAt);
        }

        @Test
        @DisplayName("자식 컬렉션을 전체 교체한다(옛 행 삭제 후 재삽입)")
        void replaces_children() {
            Product input = ProductFixtures.full(SELLER_A, CATEGORY_1);
            Product saved = productRepository.save(input);
            em.flush();
            em.clear();
            int inputValueCount = input.optionTypes()
                    .stream()
                    .mapToInt(t -> t.values()
                            .size())
                    .sum();
            assertThat(optionValueJpaRepository.count()).isEqualTo(inputValueCount);

            Product updated = productRepository.findById(saved.id())
                    .orElseThrow()
                    .toBuilder()
                    .tags(List.of("단독"))
                    .optionTypes(List.of(ProductOptionTypeModel.create("색상", null, (short) 1,
                            List.of(ProductOptionValueModel.create("검정", null, null, 0, (short) 1)))))
                    .optionConfig(null)
                    .build();
            productRepository.save(updated);
            em.flush();
            em.clear();

            Product after = productRepository.findById(saved.id())
                    .orElseThrow();
            assertThat(after.tags()).containsExactly("단독");
            assertThat(after.optionTypes()).hasSize(1);
            assertThat(after.optionTypes()
                    .get(0)
                    .values()).extracting(ProductOptionValueModel::value)
                    .containsExactly("검정");
            assertThat(after.optionConfig()).isNull();
            // 옛 옵션 타입/값 행이 남지 않았는지 행 수준으로 확인
            assertThat(optionTypeJpaRepository.count()).isEqualTo(1);
            assertThat(optionValueJpaRepository.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("상품 소유 이미지를 추가 저장한다(폴리모픽: productId 세팅, optionValueId는 null)")
        void adds_product_owned_images() {
            Product saved = productRepository.save(ProductFixtures.minimal(SELLER_A, CATEGORY_1));
            em.flush();
            em.clear();
            List<ProductImageRef> images = ProductFixtures.productImages(saved.id(), 3);

            Product withImages = productRepository.findById(saved.id())
                    .orElseThrow()
                    .toBuilder()
                    .images(images)
                    .build();
            productRepository.save(withImages);
            em.flush();
            em.clear();

            Product after = productRepository.findById(saved.id())
                    .orElseThrow();
            assertThat(after.images()).hasSize(3);
            assertThat(after.images()).allSatisfy(img -> {
                assertThat(img.productId()).isEqualTo(saved.id());
                assertThat(img.optionValueId()).isNull();
                assertThat(img.role()).isIn(ImageRole.GALLERY, ImageRole.DETAIL, ImageRole.BODY);
            });
            assertThat(after.images()).extracting(ProductImageRef::imageKey)
                    .containsExactlyInAnyOrderElementsOf(images.stream()
                            .map(ProductImageRef::imageKey)
                            .toList());
        }
    }

    @Nested
    @DisplayName("조회(파생 finder)")
    class Finders {

        @Test
        @DisplayName("카테고리로 필터링한다")
        void filters_by_category() {
            productRepository.save(ProductFixtures.minimal(SELLER_A, CATEGORY_1));
            productRepository.save(ProductFixtures.minimal(SELLER_A, CATEGORY_2));
            productRepository.save(ProductFixtures.minimal(SELLER_B, CATEGORY_1));
            em.flush();
            em.clear();

            assertThat(productRepository.findByCategoryId(CATEGORY_1)).hasSize(2)
                    .allSatisfy(p -> assertThat(p.categoryId()).isEqualTo(CATEGORY_1));
            assertThat(productRepository.findByCategoryId(9999L)).isEmpty();
        }

        @Test
        @DisplayName("findActiveBySellerId는 소프트 삭제된 상품을 제외한다 (deleted_at IS NULL)")
        void findActiveBySellerId_excludes_deleted() {
            Product live = productRepository.save(ProductFixtures.minimal(SELLER_A, CATEGORY_1));
            Product toDelete = productRepository.save(ProductFixtures.minimal(SELLER_A, CATEGORY_2));
            productRepository.save(toDelete.toBuilder()
                    .deletedAt(Instant.parse("2026-03-03T00:00:00Z"))
                    .build());
            em.flush();
            em.clear();

            assertThat(productRepository.findActiveBySellerId(SELLER_A))
                    .extracting(Product::id)
                    .containsExactly(live.id());
        }

        @Test
        @DisplayName("존재하지 않는 id 조회는 Optional.empty")
        void findById_unknown_empty() {
            assertThat(productRepository.findById(UUID.randomUUID())).isEmpty();
        }
    }

    @Nested
    @DisplayName("엣지 케이스")
    class EdgeCases {

        @Test
        @DisplayName("존재하지 않는 id로 갱신 시 예외(@Repository 예외 변환으로 JpaObjectRetrievalFailureException)")
        void update_nonexistent_throws() {
            Product ghost = ProductFixtures.minimal(SELLER_A, CATEGORY_1)
                    .toBuilder()
                    .id(UUID.randomUUID())
                    .build();

            // 구현은 jakarta EntityNotFoundException을 던지지만, @Repository 빈이라 Spring 예외 변환을 거쳐
            // JpaObjectRetrievalFailureException(원인=EntityNotFoundException)으로 호출측에 노출된다.
            assertThatThrownBy(() -> productRepository.save(ghost))
                    .isInstanceOf(JpaObjectRetrievalFailureException.class)
                    .hasCauseInstanceOf(EntityNotFoundException.class);
        }

        @Test
        @DisplayName("소프트 삭제(deletedAt)가 저장되고 findById로 확인된다")
        void soft_delete_persisted() {
            Product saved = productRepository.save(ProductFixtures.minimal(SELLER_A, CATEGORY_1));
            productRepository.save(saved.softDelete(Instant.parse("2026-03-03T00:00:00Z")));
            em.flush();
            em.clear();

            Product after = productRepository.findById(saved.id())
                    .orElseThrow();
            assertThat(after.isDeleted()).isTrue();
            assertThat(after.deletedAt()).isNotNull();
        }
    }
}
