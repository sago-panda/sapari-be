package com.sapari.product.domain.repository.ranking;

import com.sapari.product.domain.model.ranking.WeeklyBestProduct;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 주간 베스트 상품 랭킹 영속 포트. 배치가 주차별로 생성하는 append-only 집계라 {@code save}는 INSERT 전용이다.
 */
public interface WeeklyBestProductRepository {
    /**
     * 랭킹 행 1건 적재(append-only — 갱신 경로 없음).
     */
    WeeklyBestProduct save(WeeklyBestProduct best);

    Optional<WeeklyBestProduct> findById(UUID id);

    /**
     * 해당 주차(월요일 시작일)의 랭킹 목록을 순위 순으로 반환한다.
     */
    List<WeeklyBestProduct> findByWeekStart(LocalDate weekStart);
}
