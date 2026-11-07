package com.sapari.live.view;

import java.time.Instant;

/**
 * 판매자(방 주인) 방 상세 — 공개 상세의 값에 송출 설정을 더한다.
 *
 * <p>{@code rtmpUrl} 은 RTMP 송출 키를 발급받은 방만 값이 있다. streamKey 는 여기 없다 — 발급·재발급 응답으로만 전달된다.
 */
public record SellerLiveRoomDetailView(
        String title,
        String description,
        String thumbnailUrl,
        String sellerNickname,
        LiveRoomTab status,
        Instant scheduledAt,
        Instant startedAt,
        Instant endedAt,
        String vodStatus,
        String rtmpUrl
) {
}
