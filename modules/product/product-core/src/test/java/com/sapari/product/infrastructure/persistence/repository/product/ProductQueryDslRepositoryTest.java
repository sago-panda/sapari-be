package com.sapari.product.infrastructure.persistence.repository.product;

import static com.sapari.product.support.ProductFixtures.CATEGORY_1;
import static com.sapari.product.support.ProductFixtures.CATEGORY_2;
import static com.sapari.product.support.ProductFixtures.SELLER_A;
import static com.sapari.product.support.ProductFixtures.SELLER_B;
import static org.assertj.core.api.Assertions.assertThat;

import com.sapari.product.domain.model.product.ImageRole;
import com.sapari.product.domain.model.product.Product;
import com.sapari.product.domain.model.product.ProductImageRef;
import com.sapari.product.domain.model.product.ProductSummary;
import com.sapari.product.domain.repository.product.ProductRepository;
import com.sapari.product.support.AbstractRepositoryIntegrationTest;
import com.sapari.product.support.ProductFixtures;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link ProductQueryDslRepository} 통합 테스트. QueryDSL 요약 프로젝션의 정렬·대표이미지 선택·판매자 격리 등 쿼리 고유 동작을 직접 검증한다.
 * (스칼라 매핑·삭제 제외·다중 상품 썸네일 zip 등 기본 시나리오는 {@code ProductRepositoryImplTest}가 포트 경유로 검증)
 */
@DisplayName("ProductQueryDslRepository 통합 테스트")
class ProductQueryDslRepositoryTest extends AbstractRepositoryIntegrationTest {

    @Autowired
    ProductRepository productRepository;

    @Autowired
    ProductQueryDslRepository queryDslRepository;

    @PersistenceContext
    EntityManager em;

    private Product saveMinimal(UUID sellerId, Long categoryId) {
        return productRepository.save(ProductFixtures.minimal(sellerId, categoryId));
    }

    private void attachImages(Product product, List<ProductImageRef> images) {
        productRepository.save(product.toBuilder()
                .images(images)
                .build());
    }

    private static ProductImageRef gallery(UUID productId, String imageKey, short sortOrder) {
        return new ProductImageRef(null, productId, null, ImageRole.GALLERY, imageKey, sortOrder);
    }

    /**
     * 첫 페이지(위치 없음)를 넉넉한 limit으로 조회한다.
     */
    private List<ProductSummary> firstPage(UUID sellerId) {
        return queryDslRepository.findActiveSellerProductSummaries(sellerId, null, null, 100);
    }

    /**
     * 상품들의 등록 일시를 같은 값으로 맞춘다(정렬키 동률 재현). 감사 필드라 엔티티로는 바꿀 수 없어 네이티브로 갱신한다.
     */
    private void forceCreatedAt(Instant createdAt, UUID... productIds) {
        em.createNativeQuery("UPDATE product_schema.products SET created_at = :createdAt WHERE id IN (:ids)")
                .setParameter("createdAt", createdAt)
                .setParameter("ids", List.of(productIds))
                .executeUpdate();
    }

    /**
     * 커서 위치를 이어 가며 limit 단위로 끝까지 읽어 순서대로 이어 붙인다(서비스의 size + 1 판정 없이 순수 keyset만 검증).
     */
    private List<UUID> readAllPages(UUID sellerId, int pageSize) {
        List<UUID> collected = new ArrayList<>();
        Instant afterCreatedAt = null;
        UUID afterId = null;
        while (true) {
            List<ProductSummary> page =
                    queryDslRepository.findActiveSellerProductSummaries(sellerId, afterCreatedAt, afterId, pageSize);
            page.forEach(summary -> collected.add(summary.id()));
            if (page.size() < pageSize) {
                return collected;
            }
            ProductSummary last = page.get(page.size() - 1);
            afterCreatedAt = last.createdAt();
            afterId = last.id();
        }
    }

    @Test
    @DisplayName("결과는 createdAt 내림차순, 동률은 id 내림차순으로 정렬된다")
    void ordersByCreatedAtDescThenIdDesc() {
        Product a = saveMinimal(SELLER_A, CATEGORY_1);
        Product b = saveMinimal(SELLER_A, CATEGORY_1);
        Product c = saveMinimal(SELLER_A, CATEGORY_2);
        em.flush();
        forceCreatedAt(Instant.parse("2026-11-01T00:00:00Z"), a.id(), b.id());   // a·b 동률 → id로 결정
        em.clear();

        // 쿼리가 보는 값(DB 저장본)으로 기대 순서 산출 — createdAt desc, id desc
        List<UUID> expected = Stream.of(a.id(), b.id(), c.id())
                .map(id -> productRepository.findById(id)
                        .orElseThrow())
                .sorted(Comparator.comparing(Product::createdAt)
                        .thenComparing(Product::id)
                        .reversed())
                .map(Product::id)
                .toList();

        assertThat(firstPage(SELLER_A))
                .extracting(ProductSummary::id)
                .containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("요약은 DB에 저장된 등록 일시를 담는다(커서 위치의 원천)")
    void carriesStoredCreatedAt() {
        Product product = saveMinimal(SELLER_A, CATEGORY_1);
        em.flush();
        Instant stored = Instant.parse("2026-11-02T03:04:05.123456Z");
        forceCreatedAt(stored, product.id());
        em.clear();

        assertThat(firstPage(SELLER_A)).singleElement()
                .satisfies(summary -> assertThat(summary.createdAt()).isEqualTo(stored));
    }

    @Test
    @DisplayName("limit만큼만 돌려준다")
    void respectsLimit() {
        for (int i = 0; i < 4; i++) {
            saveMinimal(SELLER_A, CATEGORY_1);
        }
        em.flush();
        em.clear();

        assertThat(queryDslRepository.findActiveSellerProductSummaries(SELLER_A, null, null, 3)).hasSize(3);
    }

    @Test
    @DisplayName("위치를 주면 그 행 다음부터 돌려준다 — 위치 행 자신은 포함하지 않는다")
    void startsAfterPosition() {
        for (int i = 0; i < 3; i++) {
            saveMinimal(SELLER_A, CATEGORY_1);
        }
        em.flush();
        em.clear();
        List<ProductSummary> all = firstPage(SELLER_A);
        ProductSummary position = all.get(0);

        assertThat(queryDslRepository.findActiveSellerProductSummaries(
                SELLER_A, position.createdAt(), position.id(), 100))
                .extracting(ProductSummary::id)
                .containsExactly(all.get(1).id(), all.get(2).id());
    }

    @Test
    @DisplayName("등록 일시가 같은 행이 페이지 경계에 걸려도 중복·누락 없이 전 행을 한 번씩 읽는다")
    void keysetSurvivesTiesAcrossPageBoundaries() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            ids.add(saveMinimal(SELLER_A, CATEGORY_1).id());
        }
        em.flush();
        // 3개·2개 묶음을 같은 등록 일시로 맞춰, 크기 2 페이지의 경계가 동률 묶음 한가운데를 지나가게 한다
        forceCreatedAt(Instant.parse("2026-11-03T00:00:00Z"), ids.get(0), ids.get(1), ids.get(2));
        forceCreatedAt(Instant.parse("2026-11-02T00:00:00Z"), ids.get(3), ids.get(4));
        em.clear();
        List<UUID> expected = firstPage(SELLER_A).stream().map(ProductSummary::id).toList();

        List<UUID> paged = readAllPages(SELLER_A, 2);

        assertThat(paged).containsExactlyElementsOf(expected).doesNotHaveDuplicates().hasSize(7);
    }

    @Test
    @DisplayName("삭제된 상품은 커서 페이지에도 나오지 않는다")
    void excludesDeletedAcrossPages() {
        Product kept = saveMinimal(SELLER_A, CATEGORY_1);
        Product deleted = saveMinimal(SELLER_A, CATEGORY_1);
        productRepository.save(deleted.toBuilder().deletedAt(Instant.parse("2026-11-03T00:00:00Z")).build());
        em.flush();
        em.clear();

        assertThat(readAllPages(SELLER_A, 1)).containsExactly(kept.id());
    }

    @Test
    @DisplayName("대표이미지는 sortOrder 동률 시 imageKey 오름차순으로 결정된다")
    void thumbnailTieBreakByImageKey() {
        Product product = saveMinimal(SELLER_A, CATEGORY_1);
        attachImages(product, List.of(
                gallery(product.id(), "b.jpg", (short) 0),
                gallery(product.id(), "a.jpg", (short) 0)));
        em.flush();
        em.clear();

        assertThat(firstPage(SELLER_A))
                .singleElement()
                .satisfies(summary -> assertThat(summary.thumbnailKey()).isEqualTo("a.jpg"));
    }

    @Test
    @DisplayName("다른 판매자의 상품은 포함하지 않는다")
    void excludesOtherSellers() {
        Product mine = saveMinimal(SELLER_A, CATEGORY_1);
        saveMinimal(SELLER_B, CATEGORY_1);
        em.flush();
        em.clear();

        assertThat(firstPage(SELLER_A))
                .extracting(ProductSummary::id)
                .containsExactly(mine.id());
    }

    @Test
    @DisplayName("판매자 상품이 없으면 빈 목록을 반환한다")
    void emptyWhenNone() {
        assertThat(firstPage(UUID.randomUUID())).isEmpty();
    }
}
