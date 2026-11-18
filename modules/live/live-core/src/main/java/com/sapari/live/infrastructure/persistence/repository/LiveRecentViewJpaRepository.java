package com.sapari.live.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sapari.live.infrastructure.persistence.entity.LiveRecentViewEntity;

public interface LiveRecentViewJpaRepository extends JpaRepository<LiveRecentViewEntity, LiveRecentViewEntity.Key> {

    /**
     * (회원, 방) 행을 만들거나 시각을 덮는다. 덮는 것은 새 시각이 기존보다 <b>1분 넘게 늦을 때만</b>이다.
     * <ul>
     *   <li>단조: 비동기 워커 둘이 순서를 바꿔 써도 이른 입장이 늦은 입장을 덮지 않는다(GREATEST 와 같은 의미).</li>
     *   <li>재접속 폭주 억제: {@code viewed_at} 은 인덱스 컬럼이라 매번 갱신하면 HOT 갱신이 안 돼 bloat·WAL 이
     *       는다. 1분 안의 재입장은 행을 건드리지 않는다 — 목록 순서가 1분 안에서 늦게 반영되는 대가다.</li>
     * </ul>
     * 트랜잭션은 {@code RecentViewWriter} 가 연다.
     */
    @Modifying
    @Query(value = """
            INSERT INTO live_schema.live_recent_views (user_id, live_room_id, viewed_at)
            VALUES (:userId, :roomId, :viewedAt)
            ON CONFLICT (user_id, live_room_id)
            DO UPDATE SET viewed_at = EXCLUDED.viewed_at
            WHERE live_recent_views.viewed_at < EXCLUDED.viewed_at - INTERVAL '1 minute'
            """, nativeQuery = true)
    int upsert(@Param("userId") UUID userId, @Param("roomId") UUID roomId, @Param("viewedAt") Instant viewedAt);

    /** 최근 본 목록 첫 페이지. 인덱스 {@code (user_id, viewed_at DESC, live_room_id DESC)} 와 같은 정렬. */
    @Query("""
            SELECT v FROM LiveRecentViewEntity v
             WHERE v.userId = :userId
               AND v.viewedAt >= :since
             ORDER BY v.viewedAt DESC, v.roomId DESC
            """)
    List<LiveRecentViewEntity> findRecent(@Param("userId") UUID userId,
                                          @Param("since") Instant since,
                                          Limit limit);

    /** 최근 본 목록 다음 페이지 — {@code (viewedAt, roomId)} 가 커서보다 앞선(더 오래된) 행(keyset). */
    @Query("""
            SELECT v FROM LiveRecentViewEntity v
             WHERE v.userId = :userId
               AND v.viewedAt >= :since
               AND (v.viewedAt < :afterViewedAt
                    OR (v.viewedAt = :afterViewedAt AND v.roomId < :afterRoomId))
             ORDER BY v.viewedAt DESC, v.roomId DESC
            """)
    List<LiveRecentViewEntity> findRecentAfter(@Param("userId") UUID userId,
                                               @Param("since") Instant since,
                                               @Param("afterViewedAt") Instant afterViewedAt,
                                               @Param("afterRoomId") UUID afterRoomId,
                                               Limit limit);
}
