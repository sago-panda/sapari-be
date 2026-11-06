package com.sapari.chat.infrastructure.persistence.entity;

import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * {@code live_schema.audit_logs} 매핑.
 *
 * <p><b>이 테이블은 chat 것이 아니다.</b> 정본 DDL 이 {@code -- 공통 (도메인 전용 outbox / audit)}
 * 아래에 두었고 같은 모양이 네 스키마에 있다. 그런데 live 는 이 테이블을 <b>한 번도 주장하지
 * 않았다</b>(실측: {@code live-core} 에 audit·outbox 엔티티 0건, 저장소 전체 writer 0건). 그래서
 * 지금 쓰는 쪽인 chat 이 매핑을 갖는다 — {@code chat_ban}·{@code chat_kick_log}·
 * {@code chat_filter_word} 도 같은 모양으로 {@code live_schema} 를 명시한다.
 *
 * <p>live 가 나중에 자기 조치를 같은 테이블에 적으면 섞이는 것은 <b>행</b>이 아니라 {@code action}
 * 값이다. 그쪽은 {@code ChatAuditAction} 의 {@code CHAT_} 접두가 가른다.
 *
 * <p><b>공용 베이스 엔티티를 쓰지 않는다.</b> {@code BaseUuidEntity} 는 {@code created_at} 을
 * {@code @CreatedDate} + {@code AuditingEntityListener} 로 채우는데, 아래 쓰기가 네이티브라 그
 * 리스너가 돌지 않는다. 돌더라도 그 시각은 시스템 시계에서 오고, 감사 행의 시각은 <b>조치의
 * 시각</b>이어야 하므로 주입된 시계({@code TimeProvider})에서 와야 한다 — 형제
 * {@code ChatKickLogEntity} 가 {@code kicked_at} 에 같은 판단을 적어 두었다.
 *
 * <p><b>이 클래스로 INSERT 하지 않는다.</b> 쓰기는 네이티브 한 문장이다(형제 셋과 같다) — 덧붙이기
 * 전용 행이라 영속 컨텍스트에 올려 더티 체킹을 받을 이유가 없다. 여기서 엔티티가 하는 일은 매핑
 * 선언과, 조회 경로가 생길 때의 타입 제공이다.
 *
 * <p>{@code detail}(jsonb)·{@code ip_address} 는 <b>매핑하지 않는다.</b> {@code detail} 은 쓰기는
 * 하지만 쿼리에서 {@code CAST(... AS jsonb)} 로 넣으므로 이 타입이 알 필요가 없다 — 매핑하면
 * Hibernate jsonb 타입을 이 모듈 전역에 등록해야 하고, 그 선택이 처음 쓰는 자리에서 조용히
 * 전역이 된다. {@code ip_address} 는 쓰지 않는다({@code ChatAuditEntry} javadoc 에 이유).
 * 조회 경로가 생겨 {@code detail} 을 읽어야 하면 그때 매핑을 판단할 것.
 */
@Getter
@Entity
@Table(name = "audit_logs", schema = "live_schema")
public class ChatAuditLogEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    /** 누른 사람. 정본은 nullable 이지만(시스템 조치 자리) chat 경로는 항상 사람이다. */
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

    protected ChatAuditLogEntity() {
    }
}
