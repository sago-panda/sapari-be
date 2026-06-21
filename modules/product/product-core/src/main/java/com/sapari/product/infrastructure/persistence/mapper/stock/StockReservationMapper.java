package com.sapari.product.infrastructure.persistence.mapper.stock;

import com.sapari.product.domain.model.stock.StockReservation;
import com.sapari.product.infrastructure.persistence.entity.stock.StockReservationEntity;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/**
 * {@link StockReservation} 도메인 ↔ {@link StockReservationEntity} 변환. 평면 1:1(status는 공유 enum 패스스루)이라 MapStruct가 생성하고,
 * in-place 갱신만 mutator 기반 default로 둔다.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface StockReservationMapper {

    StockReservation toDomain(StockReservationEntity entity);

    StockReservationEntity toEntity(StockReservation domain);

    /**
     * 엔티티가 updateXxx/linkOrderItem 뮤테이터라 손으로 처리한다.
     */
    default void updateEntityFromDomain(StockReservationEntity entity, StockReservation domain) {
        entity.updateStatus(domain.status());
        entity.linkOrderItem(domain.orderItemId());
        entity.updateQuantity(domain.quantity());
        entity.updateExpiresAt(domain.expiresAt());
    }
}
