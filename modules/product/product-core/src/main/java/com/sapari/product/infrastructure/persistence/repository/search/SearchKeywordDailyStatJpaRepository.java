package com.sapari.product.infrastructure.persistence.repository.search;

import com.sapari.product.infrastructure.persistence.entity.search.SearchKeywordDailyStatEntity;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 검색어 일별 집계 Spring Data 어댑터(bigint app-TSID). 집계일 조회.
 */
public interface SearchKeywordDailyStatJpaRepository extends JpaRepository<SearchKeywordDailyStatEntity, Long> {
    List<SearchKeywordDailyStatEntity> findByDate(LocalDate date);
}
