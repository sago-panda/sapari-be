package com.sapari.product.infrastructure.persistence.mapper.search;

import com.sapari.product.domain.model.search.SearchKeywordDailyStat;
import com.sapari.product.infrastructure.persistence.entity.search.SearchKeywordDailyStatEntity;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/**
 * {@link SearchKeywordDailyStat} 도메인 ↔ {@link SearchKeywordDailyStatEntity} 변환. 평면 1:1. id는 앱 생성 TSID라
 * {@code toEntity}에서도 매핑된다.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface SearchKeywordDailyStatMapper {

    SearchKeywordDailyStat toDomain(SearchKeywordDailyStatEntity entity);

    SearchKeywordDailyStatEntity toEntity(SearchKeywordDailyStat stat);
}
