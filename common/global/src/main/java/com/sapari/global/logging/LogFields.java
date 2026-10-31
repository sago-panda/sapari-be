package com.sapari.global.logging;

/**
 * 로그 상관 필드 이름. <b>한 곳에서만 정한다</b> — 문자열이 흩어지면 오타 하나가 조용히 다른 필드를
 * 만들고, 그 줄은 검색에서 빠진 채 로그에는 멀쩡히 남는다. 실제로 {@code "requestId"} 가 네 파일에
 * 각자 적혀 있었다.
 *
 * <p><b>snake_case 인 이유</b>: 이 값들은 MDC 키이고, ECS 구조화 로깅은 MDC 를 그대로 최상위 JSON
 * 필드로 올린다. 그 JSON 의 다른 필드가 {@code log.level}·{@code error.stack_traceated}·
 * {@code process.thread.name} 처럼 전부 snake_case 라, camelCase 키를 섞으면 한 객체 안에 두 규약이
 * 공존한다. 쿼리를 쓰는 사람이 필드마다 어느 쪽인지 기억해야 하는 비용이 생기고, 그 비용은 로그를
 * 실제로 쓰기 시작한 뒤에는 되돌리기 어렵다(대시보드·알림이 이름에 묶인다).
 *
 * <p>지금 바꾸는 것이 가장 싸다 — 아직 수집기가 붙어 있지 않다({@code infra/AGENTS.md}: 매니페스트는
 * 인프라 저장소에 있고 아직 없다). 소비자가 생긴 뒤에는 이름 변경이 대시보드 마이그레이션이 된다.
 */
public final class LogFields {

    /** 한 요청의 모든 줄을 묶는 식별자. 클라에는 {@code X-Request-Id} 로 돌려준다. */
    public static final String REQUEST_ID = "request_id";

    /** 인증된 주체. 게스트·미인증 요청에는 없다 — 빈 값을 넣지 않는다(없음과 익명을 구분한다). */
    public static final String USER_ID = "user_id";

    /** WS 세션. 채팅은 요청이 아니라 세션이 수명 단위라 이쪽이 상관 축이다. */
    public static final String SESSION_ID = "session_id";

    /** 채팅방. 한 방의 장애가 전체인지 그 방만인지를 가르는 축이다. */
    public static final String ROOM_ID = "room_id";

    private LogFields() {
    }
}
