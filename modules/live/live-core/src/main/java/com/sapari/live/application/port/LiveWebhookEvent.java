package com.sapari.live.application.port;

/**
 * LiveKit webhook 이벤트의 도메인 표현. LiveKit SDK 타입(WebhookEvent)을 애플리케이션 계층에 노출하지
 * 않도록 검증 어댑터({@link WebhookVerifier})가 이 형태로 변환한다.
 *
 * <p>{@code type}은 LiveKit 이벤트 문자열(예: {@code room_finished}, {@code ingress_started},
 * {@code track_published}, {@code egress_ended}). 이벤트 종류마다 채워지는 식별자가 다르므로
 * {@code roomName}/{@code ingressId}/{@code egressId}는 해당 없으면 null이다.
 */
public record LiveWebhookEvent(
        String type,
        String roomName,
        String ingressId,
        String egressId
) {
}
