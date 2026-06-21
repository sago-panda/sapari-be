package com.sapari.product.domain.model.product;

/**
 * 상품 옵션 구성 방식.
 *
 * <p>현재는 {@code COMBINATION}(옵션 값들의 조합마다 재고·가격 단위를 두는 조합형) 하나로 고정 운영하며,
 * {@code COMPONENT}(구성형)·{@code HYBRID}(혼합형)는 스키마·도메인 차원의 향후 확장 자리표시자다.
 */
public enum ProductOptionModel {
    COMBINATION, // 옵션 조합형 (현재 고정)
    COMPONENT,   // 구성형
    HYBRID       // 혼합형
}
