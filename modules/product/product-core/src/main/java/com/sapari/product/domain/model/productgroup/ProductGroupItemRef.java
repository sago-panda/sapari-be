package com.sapari.product.domain.model.productgroup;

import java.util.UUID;

/**
 * 상품 그룹 구성 아이템 VO ({@link ProductGroupSet} 애그리거트 내부).
 *
 * <p>그룹에 묶인 상품 한 건을 {@code productId}로 참조(물리 FK 미사용)하고 노출 순서를 가진다.
 */
public record ProductGroupItemRef(
        UUID id,
        UUID productId,
        Integer sortOrder
) {

    public ProductGroupItemRef {
        if (productId == null) {
            throw new IllegalArgumentException("productId는 필수입니다.");
        }
    }

    /**
     * 영속 전(id 없이) productId·sortOrder로 생성. productId 필수, sortOrder 누락 시 {@code 0}.
     */
    public static ProductGroupItemRef of(UUID productId, Integer sortOrder) {
        return new ProductGroupItemRef(null, productId, sortOrder == null ? 0 : sortOrder);
    }
}
