package com.sapari.live.view;

import java.time.Instant;
import java.util.UUID;

/** 예정 방송 공개 목록 한 줄. 아직 시작 전이라 재생 주소·시청자 수는 없다. */
public record UpcomingLiveView(
        UUID roomId,
        UUID sellerId,
        String sellerNickname,
        String title,
        String thumbnailUrl,
        Instant scheduledAt
) {
}
