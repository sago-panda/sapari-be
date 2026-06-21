package com.sapari.product.domain.model.product;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Builder;

/**
 * 상품 애그리거트 루트. 옵션 저작(types/values/config)·태그·이미지를 내부 자식으로 포함한다. 정렬·필터용 캐시 컬럼(minPrice·hasStock·*Count·avgRating)은 앱이
 * 갱신한다.
 */
@Builder(toBuilder = true)
public record Product(
        UUID id,
        UUID sellerId,
        Long categoryId,
        String name,
        String description,
        Integer basePrice,
        ProductStatus status,
        UUID shippingPolicyId,
        Integer additionalShippingFee,
        String rejectionReason,
        Instant deletedAt,
        Integer minPrice,
        Boolean hasStock,
        Integer purchaseCount,
        BigDecimal avgRating,
        Integer reviewCount,
        Integer viewCount,
        Integer wishlistCount,
        ProductOptionModel optionModel,
        Instant searchIndexedAt,
        Instant createdAt,
        Instant updatedAt,
        List<String> tags,
        List<ProductImageRef> images,
        List<ProductOptionTypeModel> optionTypes,
        ProductOptionConfigModel optionConfig
) {
    public Product {
        if (sellerId == null) {
            throw new IllegalArgumentException("sellerId는 필수입니다.");
        }
        if (categoryId == null) {
            throw new IllegalArgumentException("categoryId는 필수입니다.");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("상품명은 필수입니다.");
        }
        if (basePrice == null || basePrice < 0) {
            throw new IllegalArgumentException("basePrice는 0 이상이어야 합니다.");
        }
        tags = (tags == null) ? List.of() : List.copyOf(tags);
        images = (images == null) ? List.of() : List.copyOf(images);
        optionTypes = (optionTypes == null) ? List.of() : List.copyOf(optionTypes);
    }

    /**
     * 신규 상품 생성. 검수 대기(PENDING_REVIEW) 상태로 시작하고 캐시 컬럼은 기본값으로 초기화한다.
     */
    public static Product create(
            UUID sellerId,
            Long categoryId,
            String name,
            String description,
            Integer basePrice,
            UUID shippingPolicyId,
            Integer additionalShippingFee,
            ProductOptionModel optionModel,
            Instant now
    ) {
        return builder()
                .sellerId(sellerId)
                .categoryId(categoryId)
                .name(name)
                .description(description)
                .basePrice(basePrice)
                .status(ProductStatus.PENDING_REVIEW)
                .shippingPolicyId(shippingPolicyId)
                .additionalShippingFee(additionalShippingFee == null ? 0 : additionalShippingFee)
                .minPrice(null)
                .hasStock(true)
                .purchaseCount(0)
                .reviewCount(0)
                .viewCount(0)
                .wishlistCount(0)
                .optionModel(optionModel == null ? ProductOptionModel.COMBINATION : optionModel)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /**
     * 검수 통과 → 판매중 전환.
     */
    public Product approve(Instant now) {
        return toBuilder().status(ProductStatus.ON_SALE)
                .rejectionReason(null)
                .updatedAt(now)
                .build();
    }

    /**
     * 검수 반려 전환.
     */
    public Product reject(String reason, Instant now) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("반려 사유는 필수입니다.");
        }
        return toBuilder().status(ProductStatus.REJECTED)
                .rejectionReason(reason)
                .updatedAt(now)
                .build();
    }

    /**
     * 논리 삭제.
     */
    public Product softDelete(Instant now) {
        return toBuilder().deletedAt(now)
                .updatedAt(now)
                .build();
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
