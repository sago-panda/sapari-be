package com.sapari.product.domain.model.product;

import java.util.UUID;

/**
 * 상품·옵션 이미지 참조 VO. 소유자 = productId XOR optionValueId (정확히 하나만 NOT NULL).
 */
public record ProductImageRef(
        UUID id,
        UUID productId,
        UUID optionValueId,
        ImageRole role,
        String imageKey,
        Short sortOrder
) {
    public ProductImageRef {
        if ((productId == null) == (optionValueId == null)) {
            throw new IllegalArgumentException("이미지 소유자는 productId 또는 optionValueId 중 정확히 하나여야 합니다.");
        }
        if (role == null) {
            throw new IllegalArgumentException("이미지 role은 필수입니다.");
        }
        if (imageKey == null || imageKey.isBlank()) {
            throw new IllegalArgumentException("imageKey는 필수입니다.");
        }
    }

    /**
     * 상품 소유 이미지 생성 (optionValueId = null).
     */
    public static ProductImageRef ofProduct(UUID productId, ImageRole role, String imageKey, Short sortOrder) {
        return new ProductImageRef(null, productId, null, role, imageKey, sortOrder);
    }

    /**
     * 옵션값 소유 이미지 생성 (productId = null). 옵션값 선택 시 상품 이미지를 오버라이드하는 색상별 이미지에 쓰인다.
     */
    public static ProductImageRef ofOptionValue(UUID optionValueId, ImageRole role, String imageKey, Short sortOrder) {
        return new ProductImageRef(null, null, optionValueId, role, imageKey, sortOrder);
    }
}
