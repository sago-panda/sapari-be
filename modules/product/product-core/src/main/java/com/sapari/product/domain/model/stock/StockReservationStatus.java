package com.sapari.product.domain.model.stock;

/**
 * 재고 선예약 상태 (도메인). 엔티티 enum과 분리(도메인은 infrastructure를 의존하지 않음).
 */
public enum StockReservationStatus {
    HELD,      // 홀드 중
    COMMITTED, // 주문/결제로 확정
    RELEASED,  // 해제
    EXPIRED    // 만료
}
