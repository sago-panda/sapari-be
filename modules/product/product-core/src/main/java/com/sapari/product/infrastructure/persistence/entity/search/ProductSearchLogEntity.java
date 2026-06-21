package com.sapari.product.infrastructure.persistence.entity.search;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 검색 로그. 직접 INSERT 금지 — Redis/Kafka 버퍼링 후 bulk COPY. id는 앱 생성 TSID(serial 미사용) — 베이스 미상속(IDENTITY 아님), created_at도
 * 없음(searched_at 사용).
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "product_search_logs", schema = "product_schema")
public class ProductSearchLogEntity {

    @Id
    private Long id;

    private String keyword;

    // users.id (FK 미설정). NULL=비로그인
    private UUID userId;

    private String sessionId;

    private Integer resultCount;

    // products.id (FK 미설정). NULL=클릭 없이 이탈
    private UUID clickedProductId;

    // product_option_combinations.id (FK 미설정)
    private UUID clickedCombinationId;

    private Instant searchedAt;

    /**
     * 빌더 전용 생성자. JPA가 요구하는 protected 기본 생성자와 구분하여 private으로 두고, MapStruct 매퍼·테스트 픽스처가 빌더로만 신규 생성/재구성하도록 강제한다.
     *
     * <p>id는 {@code @GeneratedValue}가 없는 앱 공급 TSID이므로 생성 시점에 빌더로 직접 주입한다.
     */
    @Builder
    private ProductSearchLogEntity(Long id, String keyword, UUID userId, String sessionId, Integer resultCount,
                                   UUID clickedProductId, UUID clickedCombinationId, Instant searchedAt) {
        this.id = id;
        this.keyword = keyword;
        this.userId = userId;
        this.sessionId = sessionId;
        this.resultCount = resultCount;
        this.clickedProductId = clickedProductId;
        this.clickedCombinationId = clickedCombinationId;
        this.searchedAt = searchedAt;
    }
}
