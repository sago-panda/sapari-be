package com.sapari.live.application.port;

/**
 * LiveKit webhook 이벤트의 도메인 표현. LiveKit SDK 타입(WebhookEvent)을 애플리케이션 계층에 노출하지
 * 않도록 검증 어댑터({@link WebhookVerifier})가 이 형태로 변환한다.
 *
 * <p>{@code type}은 LiveKit 이벤트 문자열(예: {@code room_finished}, {@code ingress_started},
 * {@code track_published}, {@code egress_ended}). 이벤트 종류마다 채워지는 식별자가 다르므로
 * {@code roomName}/{@code ingressId}/{@code egressId}는 해당 없으면 null이다.
 *
 * <p>{@code egressStatus} 는 egress 이벤트의 LiveKit 상태 이름(예: {@code EGRESS_COMPLETE}), {@code egressError} 는
 * egress 가 준 오류 문자열(없으면 null)이다. egress 이벤트가 아니면 둘 다 null.
 */
public record LiveWebhookEvent(
        String type,
        String roomName,
        String ingressId,
        String egressId,
        String egressStatus,
        String egressError
) {
    /** egress 상태가 필요 없는 이벤트용. */
    public LiveWebhookEvent(String type, String roomName, String ingressId, String egressId) {
        this(type, roomName, ingressId, egressId, null, null);
    }
}
