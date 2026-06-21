package com.sapari.product.domain.repository.stock;

import com.sapari.product.domain.model.stock.StockReservation;
import com.sapari.product.domain.model.stock.StockReservationStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link StockReservation} 영속 포트 — 주문前 재고 홀드 원장(체크아웃·라이브 플래시).
 *
 * <p>일반 주문은 order_items가 예약 원장이라 미사용. 조합의 {@code reserved_stock}은 여기 HELD 수량의 집계 캐시다.
 */
public interface StockReservationRepository {

    /**
     * upsert(id==null→INSERT).
     */
    StockReservation save(StockReservation reservation);

    Optional<StockReservation> findById(UUID id);

    /**
     * 조합별 특정 상태의 예약들(예: HELD 합산으로 reserved_stock 산정, 만료 회수 대상 조회).
     */
    List<StockReservation> findByCombinationIdAndStatus(UUID combinationId, StockReservationStatus status);
}
