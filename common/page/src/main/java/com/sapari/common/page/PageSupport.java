package com.sapari.common.page;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 페이지네이션 공통 헬퍼(cursor/offset 공용).
 *
 * <p>{@link #normalizeSize}는 클라이언트가 보낸 size 를 안전한 범위로 보정한다.
 * size 는 요청 파라미터(신뢰 불가 입력)이므로, 상한을 두지 않으면 {@code ?size=1000000}
 * 같은 요청이 대량 조회를 유발할 수 있다. null/0/음수는 기본값으로, 과도한 값은 최대값으로 맞춘다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PageSupport {

    public static final int DEFAULT_SIZE = 15;
    public static final int MAX_SIZE = 100;

    /** 요청 size 를 [1, MAX_SIZE] 범위로 보정한다. null/0/음수면 DEFAULT_SIZE. */
    public static int normalizeSize(Integer size) {
        if (size == null || size <= 0) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }
}
