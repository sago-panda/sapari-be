package com.sapari.product.domain.model.faq;

import java.util.UUID;

/**
 * 상품 문의 첨부 이미지 (ProductFaq 애그리거트 내부 VO).
 */
public record InquiryImageRef(
        UUID id,
        String imageKey,
        String originFileName,
        Integer sortOrder
) {
    /**
     * imageKey 필수(빈 첨부 방지).
     */
    public InquiryImageRef {
        if (imageKey == null || imageKey.isBlank()) {
            throw new IllegalArgumentException("imageKey는 필수입니다.");
        }
    }

    /**
     * 영속 전(id 없이) 첨부 이미지를 생성한다.
     */
    public static InquiryImageRef of(String imageKey, String originFileName, Integer sortOrder) {
        return new InquiryImageRef(null, imageKey, originFileName, sortOrder);
    }
}
