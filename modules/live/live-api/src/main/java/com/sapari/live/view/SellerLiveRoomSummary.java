package com.sapari.live.view;

import java.time.Instant;
import java.util.UUID;

/**
 * 판매자 내 방 목록의 한 줄. {@code endedAt} 은 종료된 방에만 있다(취소 시각은 내보내지 않는다).
 */
public record SellerLiveRoomSummary(
        UUID roomId,
        String title,
        String thumbnailUrl,
        LiveRoomTab status,
        Instant scheduledAt,
        Instant startedAt,
        Instant endedAt
) {
}
