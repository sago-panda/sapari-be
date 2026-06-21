package com.sapari.product.domain.model.stock;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

/**
 * 재고 선예약 애그리거트 루트. 상태 머신(HELD → COMMITTED/RELEASED/EXPIRED).
 */
@Builder(toBuilder = true)
public record StockReservation(
        UUID id,
        UUID combinationId,
        UUID reservedBy,
        String sessionId,
        UUID orderItemId,
        Integer quantity,
        StockReservationStatus status,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt
) {

    public StockReservation {
        if (combinationId == null) {
            throw new IllegalArgumentException("combinationId는 필수입니다.");
        }
        if (quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("quantity는 1 이상이어야 합니다.");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("expiresAt은 필수입니다.");
        }
    }

    /**
     * 신규 재고 선예약 생성. 홀드(HELD) 상태로 시작하며 {@code expiresAt} 이후 만료 대상이 된다.
     */
    public static StockReservation create(
            UUID combinationId,
            UUID reservedBy,
            String sessionId,
            Integer quantity,
            Instant expiresAt,
            Instant now) {
        return builder()
                .combinationId(combinationId)
                .reservedBy(reservedBy)
                .sessionId(sessionId)
                .quantity(quantity)
                .status(StockReservationStatus.HELD)
                .expiresAt(expiresAt)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /**
     * 주문 전환 — 홀드를 확정하고 order_item에 연결.
     */
    public StockReservation commit(UUID orderItemId, Instant now) {
        if (orderItemId == null) {
            throw new IllegalArgumentException("orderItemId는 필수입니다.");
        }
        return toBuilder()
                .orderItemId(orderItemId)
                .status(StockReservationStatus.COMMITTED)
                .updatedAt(now)
                .build();
    }

    /**
     * 홀드 해제 전환 — 결제 취소·장바구니 이탈 등으로 예약을 풀어 재고를 되돌린다.
     */
    public StockReservation release(Instant now) {
        return toBuilder().status(StockReservationStatus.RELEASED)
                .updatedAt(now)
                .build();
    }

    /**
     * 만료 전환 — expiresAt 경과한 홀드를 배치/스케줄러가 정리한다.
     */
    public StockReservation expire(Instant now) {
        return toBuilder().status(StockReservationStatus.EXPIRED)
                .updatedAt(now)
                .build();
    }
}
