package com.sapari.liveapp.controller.live.dto.constraint;

/**
 * 라이브 방 입력의 길이 한도 — {@code live_schema.live_rooms} 컬럼 길이와 같다(V1__init_live.sql).
 * 한도를 넘긴 값이 DB 에서 처음 터져 500 이 되던 것을 요청 단계 400 으로 당긴다.
 */
public final class LiveFieldLimits {

    public static final int TITLE_MAX = 100;
    public static final int DESCRIPTION_MAX = 500;
    public static final int SELLER_NICKNAME_MAX = 50;
    public static final int THUMBNAIL_URL_MAX = 500;

    private LiveFieldLimits() {
    }
}
