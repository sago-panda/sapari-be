package com.sapari.product.infrastructure.persistence.entity.search;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 검색어 일별 집계 (야간 배치). seller 대시보드 검색어 쿼리 소스. id는 앱 생성 TSID(serial 미사용) — 베이스 미상속, 타임스탬프 컬럼 없음.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "search_keyword_daily_stats", schema = "product_schema")
public class SearchKeywordDailyStatEntity {

    @Id
    private Long id;

    // 집계 기준일. date는 SQL 키워드라 컬럼명 명시.
    @Column(name = "date")
    private LocalDate date;

    private String keyword;

    // ref: users.id (role=SELLER). NULL=플랫폼 전체. 물리 FK 미사용.
    private UUID sellerId;

    // ref: products.id. NULL=키워드 단위 집계. 물리 FK 미사용.
    private UUID productId;

    private Integer searchCount;

    private Integer clickCount;

    /**
     * 빌더 전용 생성자. JPA가 요구하는 protected 기본 생성자와 구분하여 private으로 두고, MapStruct 매퍼·테스트 픽스처가 빌더로만 신규 생성/재구성하도록 강제한다.
     *
     * <p>id는 {@code @GeneratedValue}가 없는 앱 공급 TSID이므로 생성 시점에 빌더로 직접 주입한다.
     */
    @Builder
    private SearchKeywordDailyStatEntity(Long id, LocalDate date, String keyword, UUID sellerId, UUID productId,
                                         Integer searchCount, Integer clickCount) {
        this.id = id;
        this.date = date;
        this.keyword = keyword;
        this.sellerId = sellerId;
        this.productId = productId;
        this.searchCount = searchCount;
        this.clickCount = clickCount;
    }
}
