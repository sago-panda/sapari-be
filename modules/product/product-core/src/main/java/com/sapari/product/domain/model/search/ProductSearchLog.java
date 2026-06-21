package com.sapari.product.domain.model.search;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

/**
 * 검색 로그 애그리거트 루트 (append-only). id는 앱 생성 TSID(필수).
 */
@Builder(toBuilder = true)
public record ProductSearchLog(
        Long id,
        String keyword,
        UUID userId,
        String sessionId,
        Integer resultCount,
        UUID clickedProductId,
        UUID clickedCombinationId,
        Instant searchedAt
) {

    public ProductSearchLog {
        if (id == null) {
            throw new IllegalArgumentException("id(앱 생성 TSID)는 필수입니다.");
        }
        if (keyword == null || keyword.isBlank()) {
            throw new IllegalArgumentException("keyword는 필수입니다.");
        }
        if (searchedAt == null) {
            throw new IllegalArgumentException("searchedAt은 필수입니다.");
        }
    }

    /**
     * 검색 로그 한 건을 생성한다(append-only). id(앱 생성 TSID)·keyword·searchedAt 필수. resultCount 누락 시 {@code 0}. 로그성 테이블이라
     * batch-COPY 호환을 위해 id는 DB serial이 아닌 앱 공급 TSID다.
     */
    public static ProductSearchLog create(Long id, String keyword, UUID userId, String sessionId,
                                          Integer resultCount, UUID clickedProductId, UUID clickedCombinationId,
                                          Instant searchedAt) {
        return ProductSearchLog.builder()
                .id(id)
                .keyword(keyword)
                .userId(userId)
                .sessionId(sessionId)
                .resultCount(resultCount == null ? 0 : resultCount)
                .clickedProductId(clickedProductId)
                .clickedCombinationId(clickedCombinationId)
                .searchedAt(searchedAt)
                .build();
    }
}
