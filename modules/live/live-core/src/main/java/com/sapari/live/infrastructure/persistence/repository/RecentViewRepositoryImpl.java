package com.sapari.live.infrastructure.persistence.repository;

import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;

import com.sapari.live.domain.model.RecentView;
import com.sapari.live.domain.repository.RecentViewRepository;
import com.sapari.live.infrastructure.persistence.entity.LiveRecentViewEntity;

/** 필드 셋이 이름까지 같아 MapStruct 매퍼 대신 손으로 옮긴다. */
@Repository
@RequiredArgsConstructor
public class RecentViewRepositoryImpl implements RecentViewRepository {

    private final LiveRecentViewJpaRepository jpa;

    @Override
    public void upsert(RecentView view) {
        jpa.upsert(view.userId(), view.roomId(), view.viewedAt());
    }

    @Override
    public List<RecentView> findRecent(UUID userId, Instant since, Instant afterViewedAt, UUID afterRoomId, int limit) {
        List<LiveRecentViewEntity> rows = (afterViewedAt == null || afterRoomId == null)
                ? jpa.findRecent(userId, since, Limit.of(limit))
                : jpa.findRecentAfter(userId, since, afterViewedAt, afterRoomId, Limit.of(limit));
        return rows.stream()
                .map(e -> new RecentView(e.getUserId(), e.getRoomId(), e.getViewedAt()))
                .toList();
    }
}
