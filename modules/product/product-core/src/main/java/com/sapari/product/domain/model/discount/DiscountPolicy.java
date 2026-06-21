package com.sapari.product.domain.model.discount;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Builder;

/**
 * 기간·상시 할인 정책 애그리거트 루트. 대상 상품/조합 매핑을 폴딩한다.
 */
@Builder(toBuilder = true)
public record DiscountPolicy(
        UUID id,
        String name,
        String description,
        DiscountValue discountValue,
        Integer priority,
        Instant startedAt,
        Instant endedAt,
        Boolean isActive,
        UUID createdBy,
        List<UUID> productIds,
        List<UUID> combinationIds,
        Instant createdAt,
        Instant updatedAt
) {

    public DiscountPolicy {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name은 필수입니다.");
        }
        if (discountValue == null) {
            throw new IllegalArgumentException("discountValue는 필수입니다.");
        }
        if (createdBy == null) {
            throw new IllegalArgumentException("createdBy는 필수입니다.");
        }
        productIds = productIds == null ? List.of() : List.copyOf(productIds);
        combinationIds = combinationIds == null ? List.of() : List.copyOf(combinationIds);
    }

    /**
     * 신규 할인 정책을 생성한다. name·discountValue·createdBy 필수.
     *
     * <p>대상 상품/조합 id 목록은 누락 시 빈 목록으로, priority 누락 시 {@code 0}으로 채운다.
     * 생성 직후엔 항상 활성({@code isActive=true})이며, id는 영속 시점에 채워진다.
     */
    public static DiscountPolicy create(
            String name,
            String description,
            DiscountValue discountValue,
            Integer priority,
            Instant startedAt,
            Instant endedAt,
            UUID createdBy,
            List<UUID> productIds,
            List<UUID> combinationIds,
            Instant now) {
        return builder()
                .name(name)
                .description(description)
                .discountValue(discountValue)
                .priority(priority == null ? 0 : priority)
                .startedAt(startedAt)
                .endedAt(endedAt)
                .isActive(true)
                .createdBy(createdBy)
                .productIds(productIds)
                .combinationIds(combinationIds)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
