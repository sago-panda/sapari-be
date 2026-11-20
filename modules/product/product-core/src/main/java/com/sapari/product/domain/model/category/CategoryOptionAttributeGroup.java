package com.sapari.product.domain.model.category;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

/**
 * 카테고리별 권장 옵션 속성 그룹 매핑의 도메인 모델.
 *
 * <p>판매자가 상품 옵션을 등록할 때 노출할 추천 속성 그룹(드롭박스 소스)을 카테고리에 연결한다.
 * {@code categoryId}(bigint)·{@code attributeGroupId}(uuid)는 물리 FK 없이 id로만 참조한다.
 */
@Builder(toBuilder = true)
public record CategoryOptionAttributeGroup(
        UUID id,
        Long categoryId,
        UUID attributeGroupId,
        Integer sortOrder,
        Instant createdAt
) {

    public CategoryOptionAttributeGroup {
        if (categoryId == null) {
            throw new IllegalArgumentException("categoryId는 필수입니다.");
        }
        if (attributeGroupId == null) {
            throw new IllegalArgumentException("attributeGroupId는 필수입니다.");
        }
    }

    /**
     * categoryId·attributeGroupId는 필수, sortOrder 누락 시 {@code 0}.
     */
    public static CategoryOptionAttributeGroup create(Long categoryId, UUID attributeGroupId,
                                                      Integer sortOrder) {
        return CategoryOptionAttributeGroup.builder()
                .categoryId(categoryId)
                .attributeGroupId(attributeGroupId)
                .sortOrder(sortOrder == null ? 0 : sortOrder)
                .build();
    }
}
