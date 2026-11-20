package com.sapari.product.domain.model.combination;

/**
 * 재고 VO. 총 재고와 예약(잠금) 재고를 묶고 가용 재고 계산·불변식(0 <= reserved <= stock)을 보장한다.
 */
public record Stock(Integer stock, Integer reservedStock) {

    public Stock {
        if (stock == null || stock < 0) {
            throw new IllegalArgumentException("stock은 0 이상이어야 합니다.");
        }
        if (reservedStock == null || reservedStock < 0) {
            throw new IllegalArgumentException("reservedStock은 0 이상이어야 합니다.");
        }
        if (reservedStock > stock) {
            throw new IllegalArgumentException("reservedStock은 stock 이하여야 합니다.");
        }
    }

    public static Stock of(Integer stock, Integer reservedStock) {
        return new Stock(stock, reservedStock);
    }

    /**
     * 가용 재고 = stock - reservedStock.
     */
    public int availableStock() {
        return stock - reservedStock;
    }
}
