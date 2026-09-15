package com.sapari.live.infrastructure.persistence.entity;

/**
 * 라이브 상품 할인 방식.
 * <ul>
 *   <li>{@code RATE} — 정률 할인 (discountValue = 할인율 %).</li>
 *   <li>{@code FIXED_AMOUNT} — 정액 할인 (discountValue = 할인 금액).</li>
 * </ul>
 */
public enum DiscountType {
    RATE, FIXED_AMOUNT
}
