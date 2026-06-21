package com.sapari.product.infrastructure.persistence.repository.search;

import com.sapari.product.domain.model.search.SearchKeywordDailyStat;
import com.sapari.product.domain.repository.search.SearchKeywordDailyStatRepository;
import com.sapari.product.infrastructure.persistence.mapper.search.SearchKeywordDailyStatMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 검색어 일별 집계 영속 어댑터. 야간 배치 멱등 upsert(app-TSID id로 merge — 재실행 안전).
 */
@Repository
@RequiredArgsConstructor
public class SearchKeywordDailyStatRepositoryImpl implements SearchKeywordDailyStatRepository {

    private final SearchKeywordDailyStatJpaRepository jpaRepository;
    private final SearchKeywordDailyStatMapper mapper;

    @Override
    public SearchKeywordDailyStat save(SearchKeywordDailyStat stat) {
        // 야간 배치 멱등 upsert. id는 앱이 생성한 TSID(merge).
        return mapper.toDomain(jpaRepository.save(mapper.toEntity(stat)));
    }

    @Override
    public Optional<SearchKeywordDailyStat> findById(Long id) {
        return jpaRepository.findById(id)
                .map(mapper::toDomain);
    }

    @Override
    public List<SearchKeywordDailyStat> findByDate(LocalDate date) {
        return jpaRepository.findByDate(date)
                .stream()
                .map(mapper::toDomain)
                .toList();
    }
}
