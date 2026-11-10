package com.sapari.live.infrastructure.persistence.entity;

import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * {@code live_schema.live_recent_views} 매핑 — (회원, 방) 마다 한 행.
 *
 * <p><b>공용 베이스 엔티티를 쓰지 않는다.</b> 베이스는 단일 id·{@code created_at} 을 강제하는데 이 테이블은
 * 복합 PK 와 시각 하나뿐이다.
 *
 * <p><b>이 클래스로 INSERT하지 않는다.</b> 쓰기는 {@code ON CONFLICT DO UPDATE} 네이티브 쿼리가 맡는다
 * ({@code LiveRecentViewJpaRepository#upsert}) — {@code persist}/{@code merge} 는 동시 입장에서 제약 위반을
 * 던지거나 늦은 시각을 이른 시각으로 덮는다. 여기서 엔티티가 하는 일은 조회 타입 제공이다.
 */
@Getter
@Entity
@IdClass(LiveRecentViewEntity.Key.class)
@Table(name = "live_recent_views", schema = "live_schema")
public class LiveRecentViewEntity {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Id
    @Column(name = "live_room_id", nullable = false, updatable = false)
    private UUID roomId;

    @Column(name = "viewed_at", nullable = false)
    private Instant viewedAt;

    protected LiveRecentViewEntity() {
    }

    @EqualsAndHashCode
    public static class Key implements Serializable {
        private UUID userId;
        private UUID roomId;

        protected Key() {
        }
    }
}
