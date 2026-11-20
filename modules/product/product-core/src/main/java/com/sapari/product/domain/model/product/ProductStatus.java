package com.sapari.product.domain.model.product;

/**
 * 상품 판매 상태. 상품 원장에 varchar로 저장된다.
 *
 * <p>전이 흐름: {@code PENDING_REVIEW}(등록 직후) → 검수 통과 시 {@code ON_SALE} / 반려 시 {@code REJECTED},
 * 판매 중에는 {@code ON_SALE} ↔ {@code SUSPENDED}(중지/재개). <b>삭제는 상태가 아니라 deletedAt 소프트딜리트</b>로 처리하므로 별도 상태 값이 없다.
 * {@link Product#approve}/{@link Product#reject}가 이 전이를 캡슐화한다.
 */
public enum ProductStatus {
    PENDING_REVIEW, // 검수 대기
    ON_SALE,        // 판매중
    SUSPENDED,      // 판매 중지
    REJECTED        // 검수 반려
}
