package com.sapari.live.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 회원 한 명이 방 하나를 마지막으로 본 시각. (회원, 방) 마다 하나다.
 *
 * <p>상태 전이는 없다 — 다시 보면 같은 키로 시각만 갱신되고, 저장소가 시각을 뒤로 돌리지 않는다.
 */
public record RecentView(UUID userId, UUID roomId, Instant viewedAt) {

    public RecentView {
        if (userId == null) throw new IllegalArgumentException("userId는 필수입니다.");
        if (roomId == null) throw new IllegalArgumentException("roomId는 필수입니다.");
        if (viewedAt == null) throw new IllegalArgumentException("viewedAt은 필수입니다.");
    }
}
