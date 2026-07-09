package com.sapari.live.application.port;

/**
 * 검증된 LiveKit webhook 이벤트를 처리하는 핸들러. 이벤트 종류별 구현을 {@code @Component}로 등록하면
 * {@code LiveWebhookService}가 {@link #supports(String)}로 매칭해 디스패치한다.
 *
 * <p>이 수신기 브랜치는 라우팅 골격만 제공하고, 실제 처리(RTMP 방송 전이·종료 정리·고아 ingress 정리 등)는
 * 별도 작업에서 이 포트의 구현을 추가한다. 구현이 하나도 없으면 수신·검증만 하고 처리는 no-op이다.
 */
public interface LiveWebhookHandler {

    /** 이 핸들러가 처리하는 이벤트 타입인지(예: {@code room_finished}). */
    boolean supports(String eventType);

    void handle(LiveWebhookEvent event);
}
