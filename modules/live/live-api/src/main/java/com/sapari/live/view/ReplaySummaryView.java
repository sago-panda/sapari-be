package com.sapari.live.view;

import java.time.Instant;
import java.util.UUID;

/**
 * 다시보기 목록 한 줄. <b>재생 주소는 넣지 않는다</b> — 재생은 단건({@link ReplayView})의 몫이다.
 * {@code durationSeconds} 는 방송 시작~종료(초), 시작 시각이 없으면 null.
 */
public record ReplaySummaryView(
        UUID roomId,
        UUID sellerId,
        String sellerNickname,
        String title,
        String thumbnailUrl,
        Long durationSeconds,
        Instant endedAt
) {
}
