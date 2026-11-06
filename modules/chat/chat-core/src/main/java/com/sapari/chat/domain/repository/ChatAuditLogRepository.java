package com.sapari.chat.domain.repository;

import com.sapari.chat.domain.model.ChatAuditEntry;

/**
 * 감사 기록 쓰기 포트. <b>읽기는 없다</b> — v1 에 조회 엔드포인트가 없고, 없는 메서드를 미리
 * 내면 그 계약을 지키는 테스트도 미리 써야 한다.
 *
 * <p>구현은 트랜잭션을 열지 않는다. 호출자의 경계 안에서 돌아야 하고, 그 경계가 어디서 닫히는지가
 * 이 기록의 뜻을 정한다({@link ChatAuditEntry} javadoc).
 */
public interface ChatAuditLogRepository {

    /** 한 줄을 덧붙인다. 같은 조치를 두 번 적는 것을 막지 않는다 — 조치 쪽 멱등성이 그것을 정한다. */
    void append(ChatAuditEntry entry);
}
