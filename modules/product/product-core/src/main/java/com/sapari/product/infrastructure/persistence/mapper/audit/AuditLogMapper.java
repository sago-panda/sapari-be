package com.sapari.product.infrastructure.persistence.mapper.audit;

import com.sapari.product.domain.model.audit.AuditLog;
import com.sapari.product.infrastructure.persistence.entity.audit.AuditLogEntity;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/**
 * {@link AuditLog} 도메인 ↔ {@link AuditLogEntity} 변환. 평면 1:1이라 MapStruct가 전부 생성한다 (append-only — 갱신 경로 없음).
 * {@code unmappedTargetPolicy=ERROR}로 필드/컬럼 drift를 컴파일 시점에 잡는다.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface AuditLogMapper {

    AuditLog toDomain(AuditLogEntity entity);

    AuditLogEntity toEntity(AuditLog log);
}
