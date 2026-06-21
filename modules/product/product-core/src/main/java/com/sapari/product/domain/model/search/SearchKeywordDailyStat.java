package com.sapari.product.domain.model.search;

import java.time.LocalDate;
import java.util.UUID;
import lombok.Builder;

/**
 * 검색어 일별 집계 애그리거트 루트 (야간 배치, 멱등 upsert). id는 앱 생성 TSID(필수).
 */
@Builder(toBuilder = true)
public record SearchKeywordDailyStat(
        Long id,
        LocalDate date,
        String keyword,
        UUID sellerId,
        UUID productId,
        Integer searchCount,
        Integer clickCount
) {

    public SearchKeywordDailyStat {
        if (id == null) {
            throw new IllegalArgumentException("id(앱 생성 TSID)는 필수입니다.");
        }
        if (date == null) {
            throw new IllegalArgumentException("date는 필수입니다.");
        }
        if (keyword == null || keyword.isBlank()) {
            throw new IllegalArgumentException("keyword는 필수입니다.");
        }
    }

    /**
     * 검색어 일별 집계 한 건을 생성한다. id(앱 생성 TSID)·date·keyword 필수. searchCount/clickCount 누락 시 각각 {@code 0}. 야간 배치가 멱등 upsert로
     * 채우는 로그성 통계라 id는 앱 공급 TSID다.
     */
    public static SearchKeywordDailyStat create(Long id, LocalDate date, String keyword, UUID sellerId,
                                                UUID productId, Integer searchCount, Integer clickCount) {
        return SearchKeywordDailyStat.builder()
                .id(id)
                .date(date)
                .keyword(keyword)
                .sellerId(sellerId)
                .productId(productId)
                .searchCount(searchCount == null ? 0 : searchCount)
                .clickCount(clickCount == null ? 0 : clickCount)
                .build();
    }
}
