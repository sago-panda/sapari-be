package com.sapari.product.domain.model.product;

/**
 * 상품·옵션 이미지의 용도. 폴리모픽 이미지({@link ProductImageRef})에서 소유자와 함께 쓰여 노출 위치를 결정한다.
 *
 * <p>{@code GALLERY}(대표 ≤5)·{@code DETAIL}(상세 ≤10)은 옵션값(색상) 선택 시 해당 옵션값 이미지로 오버라이드되는
 * 대상이고, {@code BODY}(본문 인라인)는 항상 상품 공유, {@code SWATCH}(색칩)는 옵션값당 1개로 부분 유니크다. 개수 상한은 DB가 아니라 앱이 강제한다.
 */
public enum ImageRole {
    GALLERY, // 대표 (≤5)
    DETAIL,  // 상세 (≤10)
    BODY,    // 본문 인라인
    SWATCH   // 색칩
}
