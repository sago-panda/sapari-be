package com.sapari.chat.infrastructure.persistence.entity;

import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * {@code live_schema.chat_kick_log} 매핑.
 *
 * <p><b>공용 베이스 엔티티를 쓰지 않는다.</b> {@code BaseUuidEntity}는 {@code created_at}을 강제하는데 이
 * 테이블에는 그 컬럼이 없다 — 기록 시각은 {@code kicked_at}이다. 억지로 맞추려면 스키마를 건드려야 하고,
 * 그건 이미 머지된 Flyway를 고치는 일이다.
 *
 * <p><b>이 클래스로 INSERT하지 않는다.</b> 등록은 {@code ON CONFLICT DO NOTHING} 네이티브 쿼리가 맡는다 —
 * 중복 강퇴를 예외가 아니라 <i>무동작</i>으로 흡수해야 하는데, JPA {@code persist}는 제약 위반을 예외로
 * 던져 그 구분을 호출자에게 넘기지 못한다. 여기서 엔티티가 하는 일은 조회 타입 제공이다.
 *
 * <p>역할은 문자열로 담는다 — 같은 모듈의 {@code ChatMessageDocument}가 발신자 역할을 다루는 방식과 같고,
 * 영속 계층이 도메인 enum의 변화에 묶이지 않는다.
 */
@Getter
@Entity
@Table(name = "chat_kick_log", schema = "live_schema")
public class ChatKickLogEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    /** 강퇴 <b>대상</b> — 강퇴한 사람이 아니다. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "live_room_id", nullable = false, updatable = false)
    private UUID liveRoomId;

    @Column(name = "kicked_by_id", nullable = false, updatable = false)
    private UUID kickedById;

    @Column(name = "kicked_by_role", nullable = false, updatable = false, length = 16)
    private String kickedByRole;

    /** 마스킹 전 원문 스냅샷. 참조가 아니라 내용이라 메시지가 사라져도 남는다. */
    @Column(name = "triggering_message", nullable = false, updatable = false, columnDefinition = "text")
    private String triggeringMessage;

    @Column(name = "kicked_at", nullable = false, updatable = false)
    private Instant kickedAt;

    protected ChatKickLogEntity() {
    }
}
