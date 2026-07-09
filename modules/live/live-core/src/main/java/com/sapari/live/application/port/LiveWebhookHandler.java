package com.sapari.live.application.port;

/**
 * 검증된 LiveKit webhook 이벤트를 처리하는 핸들러. 이벤트 종류별 구현을 {@code @Component}로 등록하면
 * {@code LiveWebhookService}가 {@link #supports(String)}로 매칭해 디스패치한다.
 *
 * <p>수신기(엔드포인트·서명검증·라우팅)는 이 포트만 제공한다. 실제 처리는 각 도메인 작업이 구현을 소유한다.
 *
 * <h3>구현 계약 (구현 브랜치가 반드시 지킬 것)</h3>
 * <ul>
 *   <li><b>소유는 각 도메인 작업</b> — 핸들러는 webhook 패키지가 아니라 해당 기능(예: RTMP 방송 전이,
 *       종료 정리, 고아 ingress 정리) 쪽에 둔다. 핸들러는 webhook 이벤트를 자기 도메인 유스케이스로
 *       연결하는 <b>얇은 트리거 어댑터</b>여야 하며, 비즈니스 로직은 도메인 서비스에 둔다.
 *   <li><b>반드시 멱등</b> — LiveKit은 실패 시 webhook을 재전송하고, TTL 내 리플레이도 가능하다. 같은
 *       이벤트가 여러 번 도착해도 안전해야 한다(예: 이미 종료된 방이면 no-op, 이벤트 식별자 기반 소비 마킹).
 *   <li><b>예외 정책 인지</b> — {@code LiveWebhookService}는 핸들러 예외를 격리·로깅만 하고 200을 반환한다
 *       (LiveKit 전체 재전송에 의한 타 핸들러 중복 실행 방지). 즉 던진 예외로는 재전송이 유도되지 않으므로,
 *       유실이 치명적인 처리는 핸들러 내부에서 재시도/재조정(reconciliation)으로 보장한다.
 * </ul>
 */
public interface LiveWebhookHandler {

    /** 이 핸들러가 처리하는 이벤트 타입인지(예: {@code room_finished}). */
    boolean supports(String eventType);

    void handle(LiveWebhookEvent event);
}
