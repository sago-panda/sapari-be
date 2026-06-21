package com.sapari.product.domain.model.ranking;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Builder;

/**
 * 주간 베스트 상품 애그리거트 루트. 배치 생성(append-only).
 */
@Builder(toBuilder = true)
public record WeeklyBestProduct(
        UUID id,
        LocalDate weekStart,
        Short rank,
        UUID productId,
        Integer salesCount,
        Integer salesAmount,
        Instant createdAt
) {

    public WeeklyBestProduct {
        if (weekStart == null) {
            throw new IllegalArgumentException("weekStart는 필수입니다.");
        }
        if (rank == null) {
            throw new IllegalArgumentException("rank는 필수입니다.");
        }
        if (productId == null) {
            throw new IllegalArgumentException("productId는 필수입니다.");
        }
        if (salesCount == null) {
            throw new IllegalArgumentException("salesCount는 필수입니다.");
        }
        if (salesAmount == null) {
            throw new IllegalArgumentException("salesAmount는 필수입니다.");
        }
    }

    /**
     * 주차(weekStart)·순위(rank)·상품(productId) 기준의 주간 베스트 항목을 생성한다(셋 다 필수). id·생성 시각은 영속 시점에 채워진다.
     */
    public static WeeklyBestProduct create(LocalDate weekStart, Short rank, UUID productId,
                                           Integer salesCount, Integer salesAmount) {
        return WeeklyBestProduct.builder()
                .weekStart(weekStart)
                .rank(rank)
                .productId(productId)
                .salesCount(salesCount)
                .salesAmount(salesAmount)
                .build();
    }
}
