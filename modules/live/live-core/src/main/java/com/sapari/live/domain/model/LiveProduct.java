package com.sapari.live.domain.model;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

import com.sapari.live.domain.exception.InvalidLiveProductException;
import com.sapari.live.domain.exception.LiveErrorCode;

@Builder(toBuilder = true)
public record LiveProduct(
        UUID id,
        UUID liveRoomId,
        UUID productId,
        int originalPrice,
        int discountPrice,
        int liveDiscountPrice,
        boolean isPinned,
        int sortOrder,
        Instant pinnedAt
) {
    /**
     * LiveProduct 도메인 모델 생성
     * id는 null인 상태
     */
    public static LiveProduct create(
            UUID liveRoomId,
            UUID productId,
            int originalPrice,
            int discountPrice,
            int liveDiscountPrice,
            boolean isPinned,
            int sortOrder,
            Instant pinnedAt
    ) {
        if (liveRoomId == null) {
            throw new IllegalArgumentException("liveRoomId는 필수입니다.");
        }
        if (productId == null) {
            throw new IllegalArgumentException("productId는 필수입니다.");
        }
        validatePrices(originalPrice, discountPrice, liveDiscountPrice);
        if (isPinned && pinnedAt == null) {
            throw new IllegalArgumentException("고정 상품은 pinnedAt이 필수입니다.");
        }
        return builder()
                .liveRoomId(liveRoomId)
                .productId(productId)
                .originalPrice(originalPrice)
                .discountPrice(discountPrice)
                .liveDiscountPrice(liveDiscountPrice)
                .isPinned(isPinned)
                .sortOrder(sortOrder)
                .pinnedAt(pinnedAt)
                .build();
    }

    /**
     * 가격 규칙 — 판매자가 보낸 값이라 위반은 클라이언트 오류(LIVE-008)다.
     *
     * <p>{@code create()} 와 {@code StartLiveService} 의 사전 검증이 같이 쓴다. 서비스는 상품을 미디어 호출
     * <b>뒤</b>에 저장하므로, 여기서만 걸리면 egress 를 켰다가 롤백 보상으로 끄는 외부 호출이 생긴다.
     */
    public static void validatePrices(int originalPrice, int discountPrice, int liveDiscountPrice) {
        if (originalPrice <= 0 || discountPrice < 0 || liveDiscountPrice < 0) {
            throw invalidPrice("가격은 음수일 수 없습니다.");
        }
        if (discountPrice > originalPrice) {
            throw invalidPrice("할인가는 정가를 초과할 수 없습니다.");
        }
        if (liveDiscountPrice > discountPrice) {
            throw invalidPrice("라이브 할인가는 일반 할인가를 초과할 수 없습니다.");
        }
    }

    private static InvalidLiveProductException invalidPrice(String reason) {
        return new InvalidLiveProductException(LiveErrorCode.INVALID_LIVE_PRODUCT_PRICE, reason);
    }
}
