package com.sapari.live.view;

import java.time.Instant;
import java.util.UUID;

/** 다시보기 단건. {@code durationSeconds} 는 방송 시작~종료(초), 시작 시각이 없으면 null. */
public record ReplayView(UUID roomId, String hlsArchiveUrl, String title, Long durationSeconds, Instant endedAt) {
}
