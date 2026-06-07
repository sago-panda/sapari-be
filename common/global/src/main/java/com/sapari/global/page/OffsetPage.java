package com.sapari.global.page;

import java.util.List;

/**
 * 오프셋(페이지 번호) 기반 페이지네이션 결과(페이지 이동 + 총 개수 표시용).
 * 관리자/판매자 목록, 구매자 리뷰 목록처럼 페이지 번호 UI가 필요한 경우에 사용한다.
 * deep paging(큰 offset)에서는 성능이 저하되므로 무한 스크롤/피드에는 {@link CursorPage} 를 쓴다.
 */
public record OffsetPage<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    /**
     * 조회된 content 와 총 개수로 페이지 메타를 계산한다.
     *
     * @param content       해당 페이지에서 조회된 항목들
     * @param page          현재 페이지 번호(0-base)
     * @param size          페이지 크기(정규화된 값)
     * @param totalElements 조건에 맞는 전체 개수(count 쿼리 결과)
     */
    public static <T> OffsetPage<T> of(List<T> content, int page, int size, long totalElements) {
        int totalPages = (size <= 0) ? 0 : (int) Math.ceil((double) totalElements / size);
        return new OffsetPage<>(List.copyOf(content), page, size, totalElements, totalPages);
    }
}
