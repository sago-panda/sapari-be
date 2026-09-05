package com.sapari.chat.infrastructure.persistence.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;

/**
 * {@code live_schema.chat_ban} 매핑 — 읽기와 네이티브 INSERT에만 쓴다.
 *
 * <p>기반 엔티티를 상속하지 않는다. 이 테이블에는 {@code updated_at}이 없고, 행을 고치는 경로도 없다 —
 * 밴 해제는 갱신이 아니라 삭제다. 공통 기반을 붙이면 없는 열을 요구하게 된다.
 *
 * <p>모든 열이 {@code updatable = false}다. 밴은 만들거나 지우는 것이지 고치는 것이 아니라는 걸
 * 매핑에서부터 못 박는다.
 */
@Getter
@Entity
@Table(name = "chat_ban", schema = "live_schema")
public class ChatBanEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "banned_by_id", nullable = false, updatable = false)
    private UUID bannedById;

    /** NULL이면 영구 밴이다. */
    @Column(name = "expires_at", updatable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ChatBanEntity() {
    }
}
