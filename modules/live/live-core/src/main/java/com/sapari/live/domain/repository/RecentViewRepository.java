package com.sapari.live.domain.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.sapari.live.domain.model.RecentView;

public interface RecentViewRepository {

    /** (회원, 방) 행을 만들거나, 저장된 시각보다 <b>늦을 때만</b> 덮는다 — 순서가 뒤바뀐 쓰기가 시각을 되돌리지 않는다. */
    void upsert(RecentView view);

    /**
     * 회원의 기록 중 {@code viewedAt >= since} 인 것을 {@code viewedAt DESC, roomId DESC} 로.
     * {@code afterViewedAt}·{@code afterRoomId} 가 둘 다 null 이면 첫 페이지, 아니면 그 키보다 뒤(keyset).
     */
    List<RecentView> findRecent(UUID userId, Instant since, Instant afterViewedAt, UUID afterRoomId, int limit);
}
