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

    @Test
    @DisplayName("결과는 createdAt 내림차순·id 오름차순으로 정렬된다")
    void ordersByCreatedAtDescThenId() {
        Product a = saveMinimal(SELLER_A, CATEGORY_1);
        Product b = saveMinimal(SELLER_A, CATEGORY_1);
        Product c = saveMinimal(SELLER_A, CATEGORY_2);
        em.flush();
        em.clear();

        // 쿼리가 보는 값(DB 저장본)으로 기대 순서 산출 — createdAt desc, id asc
        List<UUID> expected = Stream.of(a.id(), b.id(), c.id())
                .map(id -> productRepository.findById(id)
                        .orElseThrow())
                .sorted(Comparator.comparing(Product::createdAt)
                        .reversed()
                        .thenComparing(Product::id))
                .map(Product::id)
                .toList();

        assertThat(queryDslRepository.findActiveSellerProductSummaries(SELLER_A))
                .extracting(ProductSummary::id)
                .containsExactlyElementsOf(expected);
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

        assertThat(queryDslRepository.findActiveSellerProductSummaries(SELLER_A))
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

        assertThat(queryDslRepository.findActiveSellerProductSummaries(SELLER_A))
                .extracting(ProductSummary::id)
                .containsExactly(mine.id());
    }

    @Test
    @DisplayName("판매자 상품이 없으면 빈 목록을 반환한다")
    void emptyWhenNone() {
        assertThat(queryDslRepository.findActiveSellerProductSummaries(UUID.randomUUID())).isEmpty();
    }
}
