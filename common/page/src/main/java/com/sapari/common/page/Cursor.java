package com.sapari.common.page;

import java.util.UUID;

/**
 * 디코딩된 단일복합 커서 — 정렬키({@code sortKey}) + 타이브레이커({@code id}). {@link CursorCodec#decode}가 돌려준다.
 *
 * <p>타입 접근자(sortKeyAsXxx/idAsXxx)가 파싱과 예외 변환을 흡수한다. 신뢰 불가 입력의 파싱 실패는
 * {@link InvalidCursorException}(400)으로 던지므로, 호출하는 repository 는 파싱·예외 처리를 반복하지 않고
 * keyset WHERE 조립에만 집중한다.
 *
 * <p>이 레코드는 "역할"(정렬키/타이브레이커)만 표현한다. "무슨 기준 정렬인가"(가격·판매량 등)는 정렬마다 다른
 * 도메인 지식이므로, 도메인별 명명 cursor 레코드(예: {@code ProductSalesCursor})가 이 코덱을 감싸 드러낸다.
 */
public record Cursor(String sortKey, String id) {

    /** 정렬키를 long 으로. 숫자 형식이 아니면 InvalidCursorException(400). */
    public long sortKeyAsLong() {
        try {
            return Long.parseLong(sortKey);
        } catch (NumberFormatException e) {
            throw new InvalidCursorException("커서 sortKey long 파싱 실패", e);
        }
    }

    /** 정렬키를 UUID 로. 형식이 아니면 InvalidCursorException(400). */
    public UUID sortKeyAsUuid() {
        try {
            return UUID.fromString(sortKey);
        } catch (IllegalArgumentException e) {
            throw new InvalidCursorException("커서 sortKey UUID 파싱 실패", e);
        }
    }

    /** 타이브레이커 id 를 UUID 로. 형식이 아니면 InvalidCursorException(400). */
    public UUID idAsUuid() {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new InvalidCursorException("커서 id UUID 파싱 실패", e);
        }
    }
}
