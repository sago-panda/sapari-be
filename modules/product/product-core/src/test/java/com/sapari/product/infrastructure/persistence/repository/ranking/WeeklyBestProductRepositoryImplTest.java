package com.sapari.product.infrastructure.persistence.repository.ranking;

import static org.assertj.core.api.Assertions.assertThat;

import com.sapari.product.domain.model.ranking.WeeklyBestProduct;
import com.sapari.product.domain.repository.ranking.WeeklyBestProductRepository;
import com.sapari.product.support.AbstractRepositoryIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link WeeklyBestProductRepositoryImpl} 통합 테스트. 배치 append-only 적재 + 주차별 조회.
 */
@DisplayName("WeeklyBestProductRepositoryImpl 통합 테스트")
class WeeklyBestProductRepositoryImplTest extends AbstractRepositoryIntegrationTest {

    private static final LocalDate WEEK = LocalDate.of(2026, 1, 5); // 월요일

    @Autowired
    WeeklyBestProductRepository repository;

    @PersistenceContext
    EntityManager em;

    @Test
    @DisplayName("랭킹 행을 적재하고 그대로 왕복 조회한다(id 생성)")
    void save_round_trips() {
        UUID productId = UUID.randomUUID();
        WeeklyBestProduct saved = repository.save(
                WeeklyBestProduct.create(WEEK, (short) 1, productId, 120, 3_600_000));
        em.flush();
        em.clear();

        WeeklyBestProduct r = repository.findById(saved.id())
                .orElseThrow();
        assertThat(r.id()).isNotNull();
        assertThat(r.weekStart()).isEqualTo(WEEK);
        assertThat(r.rank()).isEqualTo((short) 1);
        assertThat(r.productId()).isEqualTo(productId);
        assertThat(r.salesCount()).isEqualTo(120);
        assertThat(r.salesAmount()).isEqualTo(3_600_000);
        assertThat(r.createdAt()).isNotNull();
    }

    @Test
    @DisplayName("findByWeekStart는 해당 주차 랭킹만 반환한다")
    void findByWeekStart_filters() {
        // 같은 주차는 rank가 unique(week_start, rank)라 순위를 달리한다
        repository.save(WeeklyBestProduct.create(WEEK, (short) 1, UUID.randomUUID(), 100, 1_000_000));
        repository.save(WeeklyBestProduct.create(WEEK, (short) 2, UUID.randomUUID(), 90, 900_000));
        repository.save(WeeklyBestProduct.create(WEEK.minusWeeks(1), (short) 1, UUID.randomUUID(), 80, 800_000));
        em.flush();
        em.clear();

        assertThat(repository.findByWeekStart(WEEK)).hasSize(2)
                .allSatisfy(b -> assertThat(b.weekStart()).isEqualTo(WEEK));
        assertThat(repository.findByWeekStart(LocalDate.of(2030, 1, 7))).isEmpty();
    }

    @Test
    @DisplayName("존재하지 않는 id는 Optional.empty")
    void findById_unknown_empty() {
        assertThat(repository.findById(UUID.randomUUID())).isEmpty();
    }
}
