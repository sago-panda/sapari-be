package com.sapari.product.infrastructure.persistence.entity.stock;

import com.sapari.product.domain.model.stock.StockReservationStatus;

import com.sapari.storage.db.entity.UuidTimeEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 주문 이전 단계(체크아웃·라이브 플래시) 재고 선예약 원장. combination.reserved_stock은 HELD quantity 집계 캐시.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "stock_reservations", schema = "product_schema")
public class StockReservationEntity extends UuidTimeEntity {

    // ref: product_option_combinations.id. 물리 FK 미사용.
    private UUID combinationId;

    // ref: users.id. 예약 주체. 비회원은 NULL + session_id. 물리 FK 미사용.
    private UUID reservedBy;

    private String sessionId;

    // ref: order_items.id (order 도메인). 주문 전환 시 연결. 물리 FK 미사용.
    private UUID orderItemId;

    private Integer quantity;

    @Enumerated(EnumType.STRING)
    private StockReservationStatus status;

    private Instant expiresAt;

    /**
     * 빌더 전용 생성자. JPA용 protected 기본 생성자와 구분해 private으로 두고 빌더로만 생성/재구성한다.
     */
    @Builder
    private StockReservationEntity(UUID combinationId, UUID reservedBy, String sessionId, UUID orderItemId,
                                   Integer quantity, StockReservationStatus status, Instant expiresAt) {
        this.combinationId = combinationId;
        this.reservedBy = reservedBy;
        this.sessionId = sessionId;
        this.orderItemId = orderItemId;
        this.quantity = quantity;
        this.status = status;
        this.expiresAt = expiresAt;
    }

    /**
     * 예약 상태를 전이시킨다(HELD → 확정/만료/취소 등).
     */
    public void updateStatus(StockReservationStatus status) {
        this.status = status;
    }

    /**
     * 주문 전환 시 생성된 order 도메인의 order_item id를 연결한다(이후 예약-주문 추적용).
     */
    public void linkOrderItem(UUID orderItemId) {
        this.orderItemId = orderItemId;
    }

    /**
     * 예약 수량을 변경한다.
     */
    public void updateQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    /**
     * 예약 만료 시각을 변경한다(만료 시 HELD 재고 회수 기준).
     */
    public void updateExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }
}
