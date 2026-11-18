package com.sapari.live.view;

import java.time.Instant;

/**
 * 공개 방 상세 — 누구나(비로그인 포함) 본다.
 *
 * <p><b>판매자 전용 값(rtmpUrl·송출 유형)을 여기에 더하지 않는다.</b> 호출자에 따라 값을 비우는 대신 형식에서
 * 빼서, 직렬화 실수로 새는 길을 없앤다. 판매자 값은 {@link SellerLiveRoomDetailView} 의 몫이다.
 * 룸 토큰·송출 키·미디어 서버 식별자도 없다 — 입장·발급 응답의 몫이다.
 */
public record PublicLiveRoomDetailView(
        String title,
        String description,
        String thumbnailUrl,
        String sellerNickname,
        LiveRoomTab status,
        Instant scheduledAt,
        Instant startedAt,
        Instant endedAt,
        String vodStatus
) {
}
