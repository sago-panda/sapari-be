package com.sapari.product.domain.model.combination;

/**
 * SKU 코드 VO (WMS/3PL 연동용). 값이 없으면 {@code null} Sku로 표현한다.
 */
public record Sku(String value) {

    public Sku {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("sku는 비어 있을 수 없습니다.");
        }
    }

    public static Sku of(String value) {
        return value == null ? null : new Sku(value);
    }
}
