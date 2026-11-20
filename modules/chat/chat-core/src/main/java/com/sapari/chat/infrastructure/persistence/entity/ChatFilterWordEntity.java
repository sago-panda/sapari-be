package com.sapari.chat.infrastructure.persistence.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;

/**
 * {@code live_schema.chat_filter_word} 매핑 — 조회 타입이다.
 *
 * <p><b>이 클래스로 INSERT 하지 않는다.</b> 같은 모듈의 {@code ChatKickLogEntity} 와 같은 이유다 —
 * 중복 추가는 예외가 아니라 무동작이어야 하는데 JPA {@code persist} 는 유니크 위반
 * ({@code uk_chat_filter_word (kind, word)})을 예외로 던져 그 구분을 호출자에게 넘기지 못한다.
 * 추가·삭제는 네이티브 쿼리가 맡고 여기서 하는 일은 조회 타입 제공이다.
 *
 * <p>그래서 {@code BaseUuidEntity} 를 상속하지 않는다. 그쪽은 {@code @UuidGenerator} 와
 * {@code @CreatedDate} 로 <b>JPA 쓰기 경로</b>를 전제하는데, 이 테이블의 쓰기는 그 경로를 지나지
 * 않는다 — 상속하면 아무 일도 하지 않는 두 애너테이션이 "여기서 id 와 시각이 만들어진다" 고
 * 거짓으로 선언한다. 둘 다 테이블 기본값({@code gen_random_uuid()} · {@code now()})이 만든다.
 *
 * <p>{@code kind} 를 문자열로 담는다 — 같은 모듈의 {@code ChatKickLogEntity} 가 역할을 다루는
 * 방식과 같고, 영속 계층이 도메인 enum 의 변화에 묶이지 않는다.
 */
@Getter
@Entity
@Table(name = "chat_filter_word", schema = "live_schema")
public class ChatFilterWordEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    /** {@code PROFANITY} | {@code WHITELIST} — DDL 의 {@code chk_filter_kind} 가 둘만 받는다. */
    @Column(name = "kind", nullable = false, updatable = false, length = 16)
    private String kind;

    @Column(name = "word", nullable = false, updatable = false, length = 64)
    private String word;

    /** 관리자 id. 시드는 SYSTEM UUID 다. */
    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ChatFilterWordEntity() {
    }
}
