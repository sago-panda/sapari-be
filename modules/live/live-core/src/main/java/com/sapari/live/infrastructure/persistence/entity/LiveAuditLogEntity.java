package com.sapari.live.infrastructure.persistence.entity;

import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * {@code live_schema.audit_logs} 매핑 — live 쪽 관리자 조치용.
 *
 * <p>chat-core 의 {@code ChatAuditLogEntity} 가 같은 테이블을 이미 매핑한다. live-core 는 chat-core 에
 * 의존할 수 없어 따로 둔다. 두 도메인의 행은 {@code action} 접두({@code LIVE_} / {@code CHAT_})로 갈린다.
 *
 * <p>쓰기는 {@code LiveAuditLogJpaRepository} 의 네이티브 INSERT 한 문장이고, 이 클래스로 저장하지 않는다.
 * {@code detail}(jsonb)·{@code ip_address} 를 매핑하지 않는 이유는 {@code ChatAuditLogEntity} 와 같다.
 */
@Getter
@Entity
@Table(name = "audit_logs", schema = "live_schema")
public class LiveAuditLogEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Column(name = "actor_type", nullable = false, updatable = false, length = 10)
    private String actorType;

    @Column(name = "action", nullable = false, updatable = false, length = 100)
    private String action;

    @Column(name = "target_id", updatable = false)
    private UUID targetId;

    @Column(name = "target_type", updatable = false, length = 50)
    private String targetType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LiveAuditLogEntity() {
    }
}
