package com.sapari.product.view;

import java.util.UUID;

/**
 * 상품·옵션 이미지 뷰.
 *
 * @param imageId       이미지 id
 * @param role          이미지 역할명(GALLERY/DETAIL/BODY/SWATCH)
 * @param optionValueId 옵션값 종속 이미지면 그 옵션값 id, 상품 공유 이미지면 null
 * @param imageKey      스토리지 키
 * @param sortOrder     노출 순서
 */
public record ProductImageView(
        UUID imageId,
        String role,
        UUID optionValueId,
        String imageKey,
        Short sortOrder
) {
}
