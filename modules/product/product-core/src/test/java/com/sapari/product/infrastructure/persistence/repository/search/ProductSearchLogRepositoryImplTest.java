package com.sapari.product.infrastructure.persistence.repository.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.sapari.product.domain.model.search.ProductSearchLog;
import com.sapari.product.domain.repository.search.ProductSearchLogRepository;
import com.sapari.product.support.AbstractRepositoryIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link ProductSearchLogRepositoryImpl} 통합 테스트. append-only 단건 적재(앱 생성 TSID) + 검색어별 조회.
 */
@DisplayName("ProductSearchLogRepositoryImpl 통합 테스트")
class ProductSearchLogRepositoryImplTest extends AbstractRepositoryIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-01-01T12:00:00Z");

    @Autowired
    ProductSearchLogRepository repository;

    @PersistenceContext
    EntityManager em;

    @Test
    @DisplayName("앱 공급 id로 적재하고 전 필드를 그대로 왕복 조회한다")
    void save_round_trips() {
        UUID userId = UUID.randomUUID();
        UUID clickedProduct = UUID.randomUUID();
        ProductSearchLog saved = repository.save(ProductSearchLog.create(1_001L, "원피스", userId,
                "sess-1", 42, clickedProduct, null, T0));
        em.flush();
        em.clear();

        ProductSearchLog r = repository.findById(1_001L)
                .orElseThrow();
        assertThat(r.id()).isEqualTo(1_001L);
        assertThat(r.keyword()).isEqualTo("원피스");
        assertThat(r.userId()).isEqualTo(userId);
        assertThat(r.sessionId()).isEqualTo("sess-1");
        assertThat(r.resultCount()).isEqualTo(42);
        assertThat(r.clickedProductId()).isEqualTo(clickedProduct);
        assertThat(r.clickedCombinationId()).isNull();
        assertThat(r.searchedAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("findByKeyword는 해당 검색어 로그만 반환한다")
    void findByKeyword_filters() {
        repository.save(ProductSearchLog.create(2_001L, "신발", null, null, 1, null, null, T0));
        repository.save(ProductSearchLog.create(2_002L, "신발", null, null, 2, null, null, T0));
        repository.save(ProductSearchLog.create(2_003L, "가방", null, null, 3, null, null, T0));
        em.flush();
        em.clear();

        assertThat(repository.findByKeyword("신발")).hasSize(2)
                .allSatisfy(l -> assertThat(l.keyword()).isEqualTo("신발"));
        assertThat(repository.findByKeyword("없는검색어")).isEmpty();
    }

    @Test
    @DisplayName("존재하지 않는 id는 Optional.empty")
    void findById_unknown_empty() {
        assertThat(repository.findById(9_999L)).isEmpty();
    }
}
