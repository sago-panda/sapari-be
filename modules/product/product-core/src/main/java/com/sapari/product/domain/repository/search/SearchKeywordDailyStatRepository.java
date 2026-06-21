package com.sapari.product.domain.repository.search;

import com.sapari.product.domain.model.search.SearchKeywordDailyStat;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 검색어 일별 집계 영속 포트. 야간 배치가 (date, keyword, seller, product) 단위로 멱등 업서트한다(재실행 안전). seller 대시보드 검색어 쿼리 소스. id는 앱이 생성한
 * TSID(Long).
 */
public interface SearchKeywordDailyStatRepository {
    /**
     * 일별 집계 저장. 동일 키(date+keyword+seller+product) 재실행 시 멱등 업서트.
     */
    SearchKeywordDailyStat save(SearchKeywordDailyStat stat);

    Optional<SearchKeywordDailyStat> findById(Long id);

    /**
     * 특정 집계일의 통계 목록.
     */
    List<SearchKeywordDailyStat> findByDate(LocalDate date);
}
