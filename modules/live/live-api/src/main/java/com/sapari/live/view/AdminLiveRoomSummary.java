package com.sapari.live.view;

import java.time.Instant;
import java.util.UUID;

/**
 * 관리자 전체 방 목록의 한 줄. 시각 노출은 판매자 목록과 같다 — 관리자 종료한 방(CANCELLED)은 시작·끊긴
 * 시각이 있고, 판매자 예약 취소 방은 둘 다 없다.
 */
public record AdminLiveRoomSummary(
        UUID roomId,
        UUID sellerId,
        String sellerNickname,
        String title,
        String thumbnailUrl,
        LiveRoomTab status,
        Instant scheduledAt,
        Instant startedAt,
        Instant endedAt
) {
}
