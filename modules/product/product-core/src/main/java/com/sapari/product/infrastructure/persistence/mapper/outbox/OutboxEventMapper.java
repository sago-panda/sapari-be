package com.sapari.product.infrastructure.persistence.mapper.outbox;

import com.sapari.product.domain.model.outbox.OutboxEvent;
import com.sapari.product.infrastructure.persistence.entity.outbox.OutboxEventEntity;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/**
 * {@link OutboxEvent} 도메인 ↔ {@link OutboxEventEntity} 변환. 평면 1:1(id·createdAt도 앱 주입이라 매핑), 워커 처리 결과만 mutator 기반
 * default로 둔다.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface OutboxEventMapper {

    OutboxEvent toDomain(OutboxEventEntity entity);

    OutboxEventEntity toEntity(OutboxEvent event);

    /**
     * 워커 처리 결과(processed/retry/error)는 applyProcessing 뮤테이터로 갱신한다.
     */
    default void updateEntityFromDomain(OutboxEventEntity entity, OutboxEvent event) {
        entity.applyProcessing(event.processedAt(), event.retryCount(), event.lastError());
    }
}
