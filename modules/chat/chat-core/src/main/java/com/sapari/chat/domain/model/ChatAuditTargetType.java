package com.sapari.chat.domain.model;

/**
 * 감사 기록이 가리키는 대상의 종류. {@code live_schema.audit_logs.target_type}(varchar(50)) 에 들어간다.
 *
 * <p>{@link ChatAuditAction} 이 자기 대상 종류를 들고 있으므로 호출자는 이 값을 고르지 않는다 —
 * 행위와 대상 종류가 갈릴 자리를 없애려는 것이다. 새 행위를 더하면 컴파일러가 그 종류를 묻는다.
 */
public enum ChatAuditTargetType {

    /** 사용자 계정 — 강퇴·밴 해제의 대상. */
    USER,

    /** 욕설·화이트리스트 사전의 한 줄. */
    FILTER_WORD,

    /** 라이브 방 — 강퇴 명단 재적재·종료 마커 해제의 대상. */
    LIVE_ROOM
}
