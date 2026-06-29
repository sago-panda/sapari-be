package com.sapari.product.infrastructure.persistence.entity.combination;

import com.sapari.storage.db.entity.UuidTimeEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 옵션 조합별 재고·가격·SKU 관리 단위. 유효한 조합만 저장, 상품당 최대 500개. CHECK(0 <= reserved_stock <= stock)은 DB 강제.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "product_option_combinations", schema = "product_schema")
public class ProductOptionCombinationEntity extends UuidTimeEntity {

    // ref: products.id. 물리 FK 미사용.
    private UUID productId;

    private String sku;

    // 옵션값 ID 오름차순 조합 문자열 (예: "3:7:12"). 앱이 생성.
    private String combinationKey;

    private Integer originalPrice;

    private Integer price;

    private Integer stock;

    private Integer reservedStock;

    private Boolean isAvailable;

    private Instant searchIndexedAt;

    // 낙관적 락 버전 — 동시 수정 lost update 방지. Hibernate 관리(INSERT 0, UPDATE 증가). 단 벌크 단종은 JPQL에서 수동 증가.
    @Version
    private Long version;

    /**
     * 빌더 전용 생성자. JPA용 protected 기본 생성자와 구분해 private으로 두고 빌더로만 생성/재구성한다.
     */
    @Builder
    private ProductOptionCombinationEntity(UUID productId, String sku, String combinationKey, Integer originalPrice,
                                           Integer price, Integer stock, Integer reservedStock, Boolean isAvailable,
                                           Instant searchIndexedAt) {
        this.productId = productId;
        this.sku = sku;
        this.combinationKey = combinationKey;
        this.originalPrice = originalPrice;
        this.price = price;
        this.stock = stock;
        this.reservedStock = reservedStock;
        this.isAvailable = isAvailable;
        this.searchIndexedAt = searchIndexedAt;
    }

    /**
     * SKU 코드를 변경한다.
     */
    public void updateSku(String sku) {
        this.sku = sku;
    }

    /**
     * 옵션값 ID 오름차순 조합 문자열을 변경한다(앱이 생성하는 조합 식별 키).
     */
    public void updateCombinationKey(String combinationKey) {
        this.combinationKey = combinationKey;
    }

    /**
     * 정가와 판매가를 함께 갱신한다(할인 표시·실판매가 한 쌍이라 동시에 변경).
     */
    public void updatePrice(Integer originalPrice, Integer price) {
        this.originalPrice = originalPrice;
        this.price = price;
    }

    /**
     * 총 재고와 예약 재고를 함께 갱신한다.
     *
     * <p>DB의 {@code CHECK(0 <= reserved_stock <= stock)} 불변식을 만족하도록 두 값을 한 번에 맞춘다.
     */
    public void updateStock(Integer stock, Integer reservedStock) {
        this.stock = stock;
        this.reservedStock = reservedStock;
    }

    /**
     * 조합의 판매 가능 여부를 변경한다.
     */
    public void updateAvailability(Boolean isAvailable) {
        this.isAvailable = isAvailable;
    }

    /**
     * 검색 색인에 반영된 시각을 기록한다(이후 변경분 색인 재처리 기준).
     */
    public void updateSearchIndexedAt(Instant searchIndexedAt) {
        this.searchIndexedAt = searchIndexedAt;
    }
}
