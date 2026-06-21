package com.sapari.product.infrastructure.persistence.entity.ranking;

import com.sapari.storage.db.entity.BaseUuidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 주간 베스트 상품. 판매량·금액 기준 최대 30위 (배치 생성).
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "weekly_best_products", schema = "product_schema")
public class WeeklyBestProductEntity extends BaseUuidEntity {

    // 집계 기준 주 시작일 (월요일)
    private LocalDate weekStart;

    // 순위 (1~30). rank는 SQL 키워드라 컬럼명 명시.
    @Column(name = "rank")
    private Short rank;

    // ref: products.id. 물리 FK 미사용.
    private UUID productId;

    private Integer salesCount;

    private Integer salesAmount;

    /**
     * 빌더 전용 생성자. JPA가 요구하는 protected 기본 생성자와 구분하여 private으로 두고, MapStruct 매퍼·테스트 픽스처가 빌더로만 신규 생성/재구성하도록 강제한다.
     */
    @Builder
    private WeeklyBestProductEntity(LocalDate weekStart, Short rank, UUID productId, Integer salesCount,
                                    Integer salesAmount) {
        this.weekStart = weekStart;
        this.rank = rank;
        this.productId = productId;
        this.salesCount = salesCount;
        this.salesAmount = salesAmount;
    }
}
