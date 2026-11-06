package com.sapari.chat.domain.model;

/**
 * 감사 기록에 남는 관리자·판매자 조치. {@code live_schema.audit_logs.action}(varchar(100)) 에 들어간다.
 *
 * <p><b>이름에 {@code CHAT_} 를 붙이는 이유.</b> 그 테이블은 한 도메인 것이 아니다 — 정본 DDL 이
 * {@code -- 공통 (도메인 전용 outbox / audit)} 아래에 두었고, 같은 모양의 테이블이 <b>11 스키마 중
 * 10</b> 에 있다(실측: {@code CREATE TABLE <schema>.audit_logs} 10건 · {@code db/migration}
 * 디렉터리 11개 · 없는 것은 batch).
 * 지금 이 테이블에 쓰는 것은 chat 뿐이지만(실측: 이 변경 전 저장소 전체 writer 0건) live 가
 * 나중에 자기 조치를 같은 테이블에 적으면 {@code action} 값이 섞인다. 접두가 그 이름 공간을 가른다 — 컬럼을 늘리는
 * 것보다 싸고, 당직자가 {@code action LIKE 'CHAT_%'} 로 한 도메인을 떼어 볼 수 있다.
 *
 * <p>각 행위가 <b>자기 대상 종류를 들고 있다.</b> 호출자가 둘을 따로 넘기면 갈릴 수 있고, 갈린
 * 기록은 조회에서 조용히 빠진다({@code (actor_id, actor_type, created_at)} 과
 * {@code (action, created_at)} 두 인덱스가 전부라 {@code target_type} 오류는 전수로만 드러난다).
 *
 * <p>그리고 <b>{@code target_id} 를 요구하는지도</b> 들고 있다. 대상이 UUID 로 가리켜지는 행위
 * (사용자·방)와 그렇지 않은 행위(사전의 한 줄)가 섞여 있어서다 — 사전은 식별자가 단어 문자열이고,
 * 제거된 줄의 행 UUID 는 <b>가리킬 대상이 사라져</b> 감사로서 쓸모가 없다. 그래서 그쪽은
 * {@code target_id} 를 비우고 {@code detail} 이 무엇이었는지를 싣는다. 새 행위를 더하면
 * 컴파일러가 둘 다 묻는다.
 */
public enum ChatAuditAction {

    /** 방 하나에서 한 사람을 내보냈다. 누적이 임계를 넘으면 밴으로 승격된다. */
    CHAT_KICK_USER(ChatAuditTargetType.USER, true),

    /**
     * 플랫폼 밴을 풀었다. <b>이 목록에서 가장 무거운 조치다</b> — 밴만 푸는 것이 아니라 사면
     * 기준선을 남겨 730일 창의 과거 강퇴자를 전부 세지 않게 만든다.
     */
    CHAT_RELEASE_BAN(ChatAuditTargetType.USER, true),

    /** 사전에 한 줄을 더했다(욕설 또는 화이트리스트). 무엇을 더했는지는 {@code detail} 에 있다. */
    CHAT_ADD_FILTER_WORD(ChatAuditTargetType.FILTER_WORD, false),

    /** 사전에서 한 줄을 뺐다. <b>정본 행이 사라지므로</b> 무엇이었는지는 {@code detail} 에만 남는다. */
    CHAT_REMOVE_FILTER_WORD(ChatAuditTargetType.FILTER_WORD, false),

    /** 방의 강퇴 집행 명단을 정본에서 다시 깔았다(미러 손상 복구). */
    CHAT_RECONCILE_KICK_LIST(ChatAuditTargetType.LIVE_ROOM, true),

    /** 방의 종료 마커를 지웠다(오발행으로 멈춘 채팅 복구). */
    CHAT_CLEAR_ROOM_ENDED_MARKER(ChatAuditTargetType.LIVE_ROOM, true);

    private final ChatAuditTargetType targetType;
    private final boolean requiresTargetId;

    ChatAuditAction(ChatAuditTargetType targetType, boolean requiresTargetId) {
        this.targetType = targetType;
        this.requiresTargetId = requiresTargetId;
    }

    public ChatAuditTargetType targetType() {
        return targetType;
    }

    public boolean requiresTargetId() {
        return requiresTargetId;
    }
}
