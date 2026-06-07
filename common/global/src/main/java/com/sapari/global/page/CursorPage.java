package com.sapari.global.page;

import java.util.List;
import java.util.function.Function;

/**
 * 커서(keyset) 기반 페이지네이션 결과(무한 스크롤/피드용).
 *
 * <p>커서는 정렬키 + 타이브레이커(id)를 인코딩한 불투명 문자열이다({@link CursorCodec}).
 * id 를 함께 넣어 전순서를 만들기 때문에 최신순뿐 아니라 인기순/판매량순에도 쓸 수 있다.
 * 커서 디코딩과 WHERE 복합 조건 생성은 정렬마다 달라 각 도메인 repository 의 책임이다.
 */
public record CursorPage<T>(
        List<T> content,
        String nextCursor, // 불투명 커서. null 이면 마지막 페이지
        boolean hasNext
) {
    /**
     * {@code size + 1} 개를 조회해 넘긴다. 결과가 size 를 초과하면 다음 페이지가 있다고 보고
     * 잉여 1개를 잘라낸 뒤, 마지막 항목으로부터 다음 커서를 인코딩한다.
     *
     * @param rows            size+1 개로 조회한 결과
     * @param size            페이지 크기(정규화된 값)
     * @param cursorExtractor 마지막 항목 -> 다음 커서 문자열 (예: CursorCodec.encode(정렬키, id))
     */
    public static <T> CursorPage<T> of(List<T> rows, int size, Function<T, String> cursorExtractor) {
        boolean hasNext = rows.size() > size;
        List<T> content = List.copyOf(hasNext ? rows.subList(0, size) : rows);
        String nextCursor = hasNext ? cursorExtractor.apply(content.get(content.size() - 1)) : null;
        return new CursorPage<>(content, nextCursor, hasNext);
    }
}
