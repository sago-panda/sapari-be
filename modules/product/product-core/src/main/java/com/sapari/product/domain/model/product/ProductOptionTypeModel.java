package com.sapari.product.domain.model.product;

import java.util.List;
import java.util.UUID;

/**
 * 옵션 타입 (색상, 사이즈 등) + 그 값들. Product 애그리거트 내부.
 */
public record ProductOptionTypeModel(
        UUID id,
        UUID attributeGroupId,
        String name,
        Short sortOrder,
        List<ProductOptionValueModel> values
) {
    public ProductOptionTypeModel {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("옵션 타입명은 필수입니다.");
        }
        values = (values == null) ? List.of() : List.copyOf(values);
    }

    /**
     * 신규 옵션 타입 생성 (id는 영속화 시 부여하므로 null).
     */
    public static ProductOptionTypeModel create(String name, UUID attributeGroupId, Short sortOrder,
                                                List<ProductOptionValueModel> values) {
        return new ProductOptionTypeModel(null, attributeGroupId, name, sortOrder, values);
    }
}
