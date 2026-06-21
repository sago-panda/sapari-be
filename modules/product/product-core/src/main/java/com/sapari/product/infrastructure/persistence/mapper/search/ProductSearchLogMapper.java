package com.sapari.product.infrastructure.persistence.mapper.search;

import com.sapari.product.domain.model.search.ProductSearchLog;
import com.sapari.product.infrastructure.persistence.entity.search.ProductSearchLogEntity;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/**
 * {@link ProductSearchLog} 도메인 ↔ {@link ProductSearchLogEntity} 변환. 평면 1:1, append-only. id는 앱 생성 TSID라
 * {@code toEntity}에서도 매핑된다.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ProductSearchLogMapper {

    ProductSearchLog toDomain(ProductSearchLogEntity entity);

    ProductSearchLogEntity toEntity(ProductSearchLog log);
}
