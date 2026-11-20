package com.sapari.product.domain.model.product;

import java.util.UUID;

/**
 * 옵션 값 (빨강, M 등). Product 애그리거트 내부 — 옵션 타입에 종속.
 */
public record ProductOptionValueModel(
        UUID id,
        UUID attributePresetId,
        String value,
        String metadata,
        Integer priceDelta,
        Short sortOrder
) {
    public ProductOptionValueModel {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("옵션 값은 필수입니다.");
        }
        // priceDelta(옵션 추가금)는 미지정 시 0으로 정규화 — 이후 가격 계산에서 null 분기를 없앤다.
        if (priceDelta == null) {
            priceDelta = 0;
        }
    }

    /**
     * 신규 옵션 값 생성 (id는 영속화 시 부여하므로 null).
     */
    public static ProductOptionValueModel create(String value, UUID attributePresetId, String metadata,
                                                 Integer priceDelta, Short sortOrder) {
        return new ProductOptionValueModel(null, attributePresetId, value, metadata, priceDelta, sortOrder);
    }
}
