package com.sapari.product.domain.model.productgroup;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Builder;

/**
 * 판매자 큐레이션 상품 그룹의 도메인 모델 (애그리거트 루트).
 *
 * <p>판매자가 관련 상품을 묶어 기획전 등에 노출하는 단위. 구성 상품은 {@link ProductGroupItemRef}
 * 목록으로 보관(불변 복사본)하며 각 상품은 id로만 참조한다.
 */
@Builder(toBuilder = true)
public record ProductGroupSet(
        UUID id,
        UUID sellerId,
        String groupName,
        List<ProductGroupItemRef> items,
        Instant createdAt,
        Instant updatedAt
) {

    public ProductGroupSet {
        if (sellerId == null) {
            throw new IllegalArgumentException("sellerId는 필수입니다.");
        }
        items = items == null ? List.of() : List.copyOf(items);
    }

    /**
     * sellerId 필수. items 누락 시 빈 목록. id·시각은 영속 시점에 채워진다.
     */
    public static ProductGroupSet create(UUID sellerId, String groupName,
                                         List<ProductGroupItemRef> items) {
        return ProductGroupSet.builder()
                .sellerId(sellerId)
                .groupName(groupName)
                .items(items)
                .build();
    }
}
