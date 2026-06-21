package com.sapari.product.infrastructure.persistence.repository.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.sapari.product.domain.model.search.SearchKeywordDailyStat;
import com.sapari.product.domain.repository.search.SearchKeywordDailyStatRepository;
import com.sapari.product.support.AbstractRepositoryIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link SearchKeywordDailyStatRepositoryImpl} 통합 테스트. 앱 공급 id 멱등 upsert + 집계일별 조회.
 */
@DisplayName("SearchKeywordDailyStatRepositoryImpl 통합 테스트")
class SearchKeywordDailyStatRepositoryImplTest extends AbstractRepositoryIntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2026, 1, 10);

    @Autowired
    SearchKeywordDailyStatRepository repository;

    @PersistenceContext
    EntityManager em;

    @Test
    @DisplayName("전 필드를 그대로 왕복 저장한다(seller/product null 허용)")
    void save_round_trips() {
        repository.save(SearchKeywordDailyStat.create(1L, DAY, "원피스", null, null, 100, 25));
        em.flush();
        em.clear();

        SearchKeywordDailyStat r = repository.findById(1L)
                .orElseThrow();
        assertThat(r.date()).isEqualTo(DAY);
        assertThat(r.keyword()).isEqualTo("원피스");
        assertThat(r.sellerId()).isNull();
        assertThat(r.productId()).isNull();
        assertThat(r.searchCount()).isEqualTo(100);
        assertThat(r.clickCount()).isEqualTo(25);
    }

    @Test
    @DisplayName("같은 id로 재저장하면 카운트를 멱등 업서트한다(중복 INSERT 아님)")
    void resave_same_id_updates() {
        repository.save(SearchKeywordDailyStat.create(1L, DAY, "원피스", null, null, 100, 25));
        em.flush();
        em.clear();
        // 배치 재실행: 같은 id로 카운트 갱신
        repository.save(SearchKeywordDailyStat.create(1L, DAY, "원피스", null, null, 180, 40));
        em.flush();
        em.clear();

        SearchKeywordDailyStat r = repository.findById(1L)
                .orElseThrow();
        assertThat(r.searchCount()).isEqualTo(180);
        assertThat(r.clickCount()).isEqualTo(40);
        assertThat(repository.findByDate(DAY)).hasSize(1); // 중복 행 없음
    }

    @Test
    @DisplayName("findByDate는 해당 집계일 통계만 반환한다")
    void findByDate_filters() {
        // 동일 date는 (date,keyword,seller,product) unique라 keyword를 달리한다
        repository.save(SearchKeywordDailyStat.create(10L, DAY, "신발", null, null, 5, 1));
        repository.save(SearchKeywordDailyStat.create(11L, DAY, "가방", null, null, 6, 2));
        repository.save(SearchKeywordDailyStat.create(12L, DAY.plusDays(1), "신발", null, null, 7, 3));
        em.flush();
        em.clear();

        assertThat(repository.findByDate(DAY)).hasSize(2)
                .allSatisfy(s -> assertThat(s.date()).isEqualTo(DAY));
        assertThat(repository.findByDate(LocalDate.of(2030, 1, 1))).isEmpty();
    }

    @Test
    @DisplayName("존재하지 않는 id는 Optional.empty")
    void findById_unknown_empty() {
        assertThat(repository.findById(9_999L)).isEmpty();
    }
}
