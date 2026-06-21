package com.sapari.product.infrastructure.persistence.mapper.ranking;

import com.sapari.product.domain.model.ranking.WeeklyBestProduct;
import com.sapari.product.infrastructure.persistence.entity.ranking.WeeklyBestProductEntity;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/**
 * {@link WeeklyBestProduct} 도메인 ↔ {@link WeeklyBestProductEntity} 변환. 평면 1:1, append-only(갱신 없음).
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface WeeklyBestProductMapper {

    WeeklyBestProduct toDomain(WeeklyBestProductEntity entity);

    WeeklyBestProductEntity toEntity(WeeklyBestProduct best);
}
