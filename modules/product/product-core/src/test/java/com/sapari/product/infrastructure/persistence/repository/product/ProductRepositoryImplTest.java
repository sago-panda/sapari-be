package com.sapari.product.infrastructure.persistence.repository.product;

import static com.sapari.product.support.ProductFixtures.CATEGORY_1;
import static com.sapari.product.support.ProductFixtures.CATEGORY_2;
import static com.sapari.product.support.ProductFixtures.SELLER_A;
import static com.sapari.product.support.ProductFixtures.SELLER_B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sapari.product.domain.model.combination.CombinationKey;
import com.sapari.product.domain.model.combination.ProductOptionCombination;
import com.sapari.product.domain.model.combination.Stock;
import com.sapari.product.domain.model.product.ImageRole;
import com.sapari.product.domain.model.product.Product;
import com.sapari.product.domain.model.product.ProductImageRef;
import com.sapari.product.domain.model.product.ProductOptionConfigModel;
import com.sapari.product.domain.model.product.ProductOptionTypeModel;
import com.sapari.product.domain.model.product.ProductOptionValueModel;
import com.sapari.product.domain.model.product.ProductStatus;
import com.sapari.product.domain.model.product.ProductSummary;
import com.sapari.product.domain.repository.combination.ProductOptionCombinationRepository;
import com.sapari.product.domain.repository.product.ProductRepository;
import com.sapari.product.support.AbstractRepositoryIntegrationTest;
import com.sapari.product.support.ProductFixtures;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
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
    @Autowired
    ProductOptionCombinationRepository combinationRepository;

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
    @DisplayName("옵션 config 단독 교체(replaceOptionConfig)")
    class ReplaceOptionConfig {

        private static final String SURCHARGE_JSON = "[{\"valueIds\":[\"00000000-0000-0000-0000-000000000001\"],\"amount\":700}]";
        private static final String EXCLUSION_JSON = "[{\"valueIds\":[\"00000000-0000-0000-0000-000000000002\"],\"reason\":\"미생산\"}]";

        private Product reload(UUID productId) {
            em.flush();
            em.clear();
            return productRepository.findById(productId)
                    .orElseThrow();
        }

        /** 옵션 타입/값 id 목록 — 교체 전후로 재발급되지 않았는지 비교하는 기준. */
        private List<UUID> optionValueIds(Product product) {
            return product.optionTypes()
                    .stream()
                    .flatMap(t -> t.values()
                            .stream())
                    .map(ProductOptionValueModel::id)
                    .sorted()
                    .toList();
        }

        @Test
        @DisplayName("config 행이 없으면 새로 만든다")
        void inserts_when_absent() {
            Product saved = productRepository.save(ProductFixtures.minimal(SELLER_A, CATEGORY_1));

            productRepository.replaceOptionConfig(saved.id(), ProductOptionConfigModel.of(SURCHARGE_JSON, EXCLUSION_JSON));

            Product after = reload(saved.id());
            // jsonb는 정규화(공백/키 순서)되므로 부분 비교
            assertThat(after.optionConfig()
                    .surchargeRules()).contains("\"amount\"")
                    .contains("700");
            assertThat(after.optionConfig()
                    .exclusionRules()).contains("\"reason\"")
                    .contains("미생산");
        }

        @Test
        @DisplayName("기존 행이 있으면 두 룰 컬럼을 함께 교체한다(한쪽을 null로 비우는 것 포함)")
        void updates_when_present() {
            Product saved = productRepository.save(ProductFixtures.full(SELLER_A, CATEGORY_1));
            em.flush();
            em.clear();

            productRepository.replaceOptionConfig(saved.id(), ProductOptionConfigModel.of(null, EXCLUSION_JSON));

            Product after = reload(saved.id());
            assertThat(after.optionConfig()
                    .surchargeRules()).isNull();
            assertThat(after.optionConfig()
                    .exclusionRules()).contains("미생산");
        }

        @Test
        @DisplayName("null이면 config 행을 삭제한다")
        void deletes_when_null() {
            Product saved = productRepository.save(ProductFixtures.full(SELLER_A, CATEGORY_1));
            em.flush();
            em.clear();

            productRepository.replaceOptionConfig(saved.id(), null);

            assertThat(reload(saved.id()).optionConfig()).isNull();
        }

        @Test
        @DisplayName("null인데 행이 없어도 예외 없이 끝난다")
        void null_without_row_is_noop() {
            Product saved = productRepository.save(ProductFixtures.minimal(SELLER_A, CATEGORY_1));

            productRepository.replaceOptionConfig(saved.id(), null);

            assertThat(reload(saved.id()).optionConfig()).isNull();
        }

        @Test
        @DisplayName("옵션 타입/값 행을 건드리지 않는다 — 옵션값 id가 재발급되지 않아 조합 참조가 유지된다")
        void keeps_option_value_ids() {
            Product saved = productRepository.save(ProductFixtures.full(SELLER_A, CATEGORY_1));
            List<UUID> before = optionValueIds(reload(saved.id()));

            productRepository.replaceOptionConfig(saved.id(), ProductOptionConfigModel.of(SURCHARGE_JSON, null));

            Product after = reload(saved.id());
            assertThat(before).isNotEmpty();
            assertThat(optionValueIds(after)).isEqualTo(before);
        }

        @Test
        @DisplayName("같은 트랜잭션에서 save(옛 config 재기록) 직후 교체해도 새 룰이 남는다 (옵션 수정 유스케이스 경로)")
        void replaces_right_after_save_in_same_transaction() {
            Product saved = productRepository.save(ProductFixtures.full(SELLER_A, CATEGORY_1));
            em.flush();
            em.clear();
            Product loaded = productRepository.findById(saved.id())
                    .orElseThrow();

            // flush/clear 없이 연속 호출 — save가 옛 config를 관리 엔티티로 올린 상태에서 교체
            productRepository.save(loaded);
            productRepository.replaceOptionConfig(saved.id(), ProductOptionConfigModel.of(null, EXCLUSION_JSON));

            Product after = reload(saved.id());
            assertThat(after.optionConfig()
                    .surchargeRules()).isNull();
            assertThat(after.optionConfig()
                    .exclusionRules()).contains("미생산");
        }
    }


    @Nested
    @DisplayName("조합 파생 캐시 재계산(refreshStockPriceCache)")
    class RefreshStockPriceCache {

        private static final Instant T = Instant.parse("2026-09-15T00:00:00Z");

        private Product persistProduct() {
            Product saved = productRepository.save(ProductFixtures.minimal(SELLER_A, CATEGORY_1));
            em.flush();
            return saved;
        }

        private ProductOptionCombination combo(UUID productId, String key, int price, int stock, int reserved) {
            return ProductOptionCombination.create(
                    productId, CombinationKey.of(key), null, null, price, Stock.of(stock, reserved), List.of(), T);
        }

        private Product refreshAndReload(UUID productId) {
            productRepository.refreshStockPriceCache(productId, T);
            em.flush();
            em.clear();
            return productRepository.findById(productId)
                    .orElseThrow();
        }

        @Test
        @DisplayName("min_price는 판매가능·가용재고 있는 조합의 최저가, has_stock=true — 단종·가용재고 0(예약으로 소진) 조합은 제외")
        void usesOnlySellableCombinations() {
            UUID productId = persistProduct().id();
            List<ProductOptionCombination> saved = combinationRepository.saveAll(List.of(
                    combo(productId, "k-sellable-high", 15_000, 5, 0),
                    combo(productId, "k-sellable-low", 12_000, 5, 4),
                    combo(productId, "k-reserved-out", 1_000, 3, 3),
                    combo(productId, "k-discontinued", 500, 10, 0)));
            combinationRepository.save(saved.get(3).discontinue(T));

            Product after = refreshAndReload(productId);

            assertThat(after.minPrice()).isEqualTo(12_000);
            assertThat(after.hasStock()).isTrue();
        }

        @Test
        @DisplayName("승자 할인이 있으면 할인 후 최종가로 최저가를 계산한다 (DDL: MIN(COALESCE(final_price, price)))")
        void usesWinningDiscountFinalPrice() {
            UUID productId = persistProduct().id();
            List<ProductOptionCombination> saved = combinationRepository.saveAll(List.of(
                    combo(productId, "k-a", 10_000, 5, 0),
                    combo(productId, "k-b", 9_000, 5, 0)));
            em.flush();
            em.createNativeQuery("""
                            INSERT INTO product_schema.combination_winning_discounts
                                (combination_id, policy_id, discount_amount, final_price, resolved_at)
                            VALUES (:combinationId, :policyId, 3000, 7000, :now)
                            """)
                    .setParameter("combinationId", saved.get(0).id())
                    .setParameter("policyId", UUID.randomUUID())
                    .setParameter("now", T)
                    .executeUpdate();

            assertThat(refreshAndReload(productId).minPrice()).isEqualTo(7_000);
        }

        @Test
        @DisplayName("판매 가능한 재고 조합이 없으면 min_price=null, has_stock=false")
        void noSellableCombinationMeansNullAndFalse() {
            UUID productId = persistProduct().id();
            combinationRepository.saveAll(List.of(combo(productId, "k-empty", 10_000, 0, 0)));

            Product after = refreshAndReload(productId);

            assertThat(after.minPrice()).isNull();
            assertThat(after.hasStock()).isFalse();
        }

        @Test
        @DisplayName("파생 캐시 갱신은 상품 version을 올리지 않고(수정 폼 version 유지) updated_at은 갱신한다(검색 재색인 대상)")
        void keepsVersionButTouchesUpdatedAt() {
            Product before = persistProduct();
            em.clear();
            Product loaded = productRepository.findById(before.id())
                    .orElseThrow();
            combinationRepository.saveAll(List.of(combo(before.id(), "k-v", 10_000, 1, 0)));

            Product after = refreshAndReload(before.id());

            assertThat(after.version()).isEqualTo(loaded.version());
            assertThat(after.updatedAt()).isEqualTo(T);
        }

        @Test
        @DisplayName("다른 상품의 조합은 계산에 섞이지 않고, 다른 상품의 캐시도 바뀌지 않는다")
        void isolatesOtherProducts() {
            UUID target = persistProduct().id();
            Product other = productRepository.save(ProductFixtures.aProduct(SELLER_A, CATEGORY_1)
                    .set("minPrice", 777)
                    .set("hasStock", false)
                    .sample());
            combinationRepository.saveAll(List.of(
                    combo(target, "k-t", 10_000, 5, 0),
                    combo(other.id(), "k-cheap", 100, 5, 0)));

            Product targetAfter = refreshAndReload(target);
            Product otherAfter = productRepository.findById(other.id())
                    .orElseThrow();

            assertThat(targetAfter.minPrice()).isEqualTo(10_000);
            assertThat(otherAfter.minPrice()).isEqualTo(777);
            assertThat(otherAfter.hasStock()).isFalse();
        }

        @Test
        @DisplayName("판매 불가 조합에 붙은 승자 할인은 최저가에 반영하지 않는다")
        void ignoresDiscountOnUnsellableCombination() {
            UUID productId = persistProduct().id();
            List<ProductOptionCombination> saved = combinationRepository.saveAll(List.of(
                    combo(productId, "k-sellable", 10_000, 5, 0),
                    combo(productId, "k-soldout", 9_000, 0, 0)));
            em.flush();
            em.createNativeQuery("""
                            INSERT INTO product_schema.combination_winning_discounts
                                (combination_id, policy_id, discount_amount, final_price, resolved_at)
                            VALUES (:combinationId, :policyId, 8000, 1000, :now)
                            """)
                    .setParameter("combinationId", saved.get(1).id())
                    .setParameter("policyId", UUID.randomUUID())
                    .setParameter("now", T)
                    .executeUpdate();

            assertThat(refreshAndReload(productId).minPrice()).isEqualTo(10_000);
        }

        @Test
        @DisplayName("존재하지 않는 상품 id면 아무 행도 바꾸지 않고 예외 없이 끝난다")
        void unknownProductIsNoop() {
            UUID unknown = UUID.randomUUID();

            productRepository.refreshStockPriceCache(unknown, T);
            em.flush();

            assertThat(productRepository.findById(unknown)).isEmpty();
        }

        @Test
        @DisplayName("재계산 뒤 같은 트랜잭션에서 상품을 저장해도 영속성 컨텍스트의 옛 캐시로 덮어쓰지 않는다")
        void laterProductSaveDoesNotOverwriteCache() {
            UUID productId = persistProduct().id();
            em.clear();
            Product stale = productRepository.findById(productId)
                    .orElseThrow();
            combinationRepository.saveAll(List.of(combo(productId, "k-o", 4_321, 1, 0)));
            productRepository.refreshStockPriceCache(productId, T);

            // 재계산 전에 읽은 스냅샷(옛 minPrice·hasStock)으로 다른 필드를 바꿔 저장
            productRepository.save(stale.toBuilder()
                    .name("이름만 수정")
                    .build());
            em.flush();
            em.clear();

            Product after = productRepository.findById(productId)
                    .orElseThrow();
            assertThat(after.name()).isEqualTo("이름만 수정");
            assertThat(after.minPrice()).isEqualTo(4_321);
            assertThat(after.hasStock()).isTrue();
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
        @DisplayName("findActiveSellerProductSummaries는 삭제 상품을 제외하고 스칼라·대표이미지(GALLERY 최소 sortOrder)를 채운다")
        void findActiveSellerProductSummaries_maps_and_excludes_deleted() {
            // 최저가·재고유무는 갱신 save로 덮어쓸 수 없는 파생 캐시라(refreshStockPriceCache 전담) INSERT 시점 값으로 둔다
            Product live = productRepository.save(ProductFixtures.aProduct(SELLER_A, CATEGORY_1)
                    .set("minPrice", 9_000)
                    .set("hasStock", true)
                    .sample());
            productRepository.save(live.toBuilder()
                    .status(ProductStatus.ON_SALE)
                    .reviewCount(5)
                    .avgRating(new BigDecimal("4.5"))
                    .images(List.of(
                            new ProductImageRef(null, live.id(), null, ImageRole.GALLERY, "g1.jpg", (short) 1),
                            new ProductImageRef(null, live.id(), null, ImageRole.GALLERY, "g0.jpg", (short) 0),
                            new ProductImageRef(null, live.id(), null, ImageRole.DETAIL, "d0.jpg", (short) 0)))
                    .build());
            Product deleted = productRepository.save(ProductFixtures.minimal(SELLER_A, CATEGORY_2));
            productRepository.save(deleted.toBuilder()
                    .deletedAt(Instant.parse("2026-03-03T00:00:00Z"))
                    .build());
            em.flush();
            em.clear();

            List<ProductSummary> summaries = productRepository.findActiveSellerProductSummaries(SELLER_A);
            assertThat(summaries).extracting(ProductSummary::id).containsExactly(live.id());
            assertThat(summaries.get(0)).satisfies(s -> {
                assertThat(s.status()).isEqualTo(ProductStatus.ON_SALE);
                assertThat(s.minPrice()).isEqualTo(9_000);
                assertThat(s.hasStock()).isTrue();
                assertThat(s.reviewCount()).isEqualTo(5);
                assertThat(s.avgRating()).isEqualByComparingTo("4.5");
                assertThat(s.thumbnailKey()).isEqualTo("g0.jpg"); // GALLERY 최소 sortOrder, DETAIL 제외
            });
        }

        @Test
        @DisplayName("findActiveSellerProductSummaries는 여러 상품의 대표이미지를 각 상품에 올바르게 매핑한다 (IN 배치 zip)")
        void findActiveSellerProductSummaries_thumbnail_per_product() {
            Product p1 = productRepository.save(ProductFixtures.minimal(SELLER_A, CATEGORY_1));
            productRepository.save(p1.toBuilder()
                    .images(List.of(new ProductImageRef(null, p1.id(), null, ImageRole.GALLERY, "p1-g.jpg", (short) 0)))
                    .build());
            Product p2 = productRepository.save(ProductFixtures.minimal(SELLER_A, CATEGORY_2));
            productRepository.save(p2.toBuilder()
                    .images(List.of(new ProductImageRef(null, p2.id(), null, ImageRole.GALLERY, "p2-g.jpg", (short) 0)))
                    .build());
            em.flush();
            em.clear();

            List<ProductSummary> summaries = productRepository.findActiveSellerProductSummaries(SELLER_A);
            assertThat(summaries).filteredOn(s -> s.id().equals(p1.id()))
                    .singleElement().satisfies(s -> assertThat(s.thumbnailKey()).isEqualTo("p1-g.jpg"));
            assertThat(summaries).filteredOn(s -> s.id().equals(p2.id()))
                    .singleElement().satisfies(s -> assertThat(s.thumbnailKey()).isEqualTo("p2-g.jpg"));
        }

        @Test
        @DisplayName("findActiveSellerProductSummaries는 GALLERY 이미지가 없으면 thumbnailKey가 null이다")
        void findActiveSellerProductSummaries_nullThumbnail_whenNoGallery() {
            Product live = productRepository.save(ProductFixtures.minimal(SELLER_A, CATEGORY_1));
            productRepository.save(live.toBuilder()
                    .images(List.of(
                            new ProductImageRef(null, live.id(), null, ImageRole.DETAIL, "d0.jpg", (short) 0)))
                    .build());
            em.flush();
            em.clear();

            assertThat(productRepository.findActiveSellerProductSummaries(SELLER_A))
                    .singleElement()
                    .satisfies(s -> assertThat(s.thumbnailKey()).isNull());
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

        @Test
        @DisplayName("findActiveById: 활성 상품은 루트+자식(태그·옵션·config)까지 조립해 반환한다")
        void findActiveById_returns_active_with_aggregate() {
            Product input = ProductFixtures.full(SELLER_A, CATEGORY_1);
            Product active = productRepository.save(input);
            em.flush();
            em.clear();

            assertThat(productRepository.findActiveById(active.id()))
                    .get()
                    .satisfies(p -> {
                        assertThat(p.id()).isEqualTo(active.id());
                        assertThat(p.isDeleted()).isFalse();
                        assertThat(p.deletedAt()).isNull();
                        // 자식까지 조립됐는지(loadAggregate 실행) — 빈 껍데기가 아니라 전체 애그리거트
                        assertThat(p.name()).isEqualTo(input.name());
                        assertThat(p.tags()).containsExactlyInAnyOrderElementsOf(input.tags());
                        assertThat(p.optionTypes()).hasSameSizeAs(input.optionTypes());
                        assertThat(p.optionConfig()).isNotNull();
                    });
        }

        @Test
        @DisplayName("findActiveById: 소프트 삭제 상품은 쿼리에서 제외(빈 결과)하되 delete-inclusive findById로는 여전히 조회된다")
        void findActiveById_excludes_softDeleted() {
            Instant deletedAt = Instant.parse("2026-03-03T00:00:00Z");
            Product active = productRepository.save(ProductFixtures.full(SELLER_A, CATEGORY_1));
            Product deleted = productRepository.save(ProductFixtures.full(SELLER_A, CATEGORY_1));
            productRepository.save(deleted.softDelete(deletedAt));
            em.flush();
            em.clear();

            // 같은 판매자의 활성 상품은 영향 없이 조회되고, 삭제 상품만 제외된다
            assertThat(productRepository.findActiveById(active.id())).isPresent();
            assertThat(productRepository.findActiveById(deleted.id())).isEmpty();

            // delete-inclusive findById로는 삭제 상품도 조회됨 — 제외가 쿼리 레벨임을 대비로 증명
            assertThat(productRepository.findById(deleted.id()))
                    .get()
                    .satisfies(p -> {
                        assertThat(p.isDeleted()).isTrue();
                        assertThat(p.deletedAt()).isEqualTo(deletedAt);
                    });

            // 존재하지 않는 id도 빈 결과
            assertThat(productRepository.findActiveById(UUID.randomUUID())).isEmpty();
        }
    }
}
