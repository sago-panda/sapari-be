package com.sapari.product.infrastructure.persistence.repository.stock;

import com.sapari.product.infrastructure.persistence.entity.stock.StockReservationEntity;

import com.sapari.product.domain.model.stock.StockReservationStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@code stock_reservations}(주문前 홀드 원장) Spring Data JPA 어댑터.
 */
public interface StockReservationJpaRepository extends JpaRepository<StockReservationEntity, UUID> {

    List<StockReservationEntity> findByCombinationIdAndStatus(UUID combinationId, StockReservationStatus status);
}
