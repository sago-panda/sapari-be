package com.sapari.live.infrastructure.media;

import io.livekit.server.WebhookReceiver;
import livekit.LivekitWebhook.WebhookEvent;

import java.nio.charset.StandardCharsets;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import com.sapari.live.application.port.LiveWebhookEvent;
import com.sapari.live.application.port.WebhookVerifier;
import com.sapari.live.domain.exception.InvalidWebhookException;

/**
 * LiveKit SDK {@link WebhookReceiver}로 webhook 서명을 검증하는 어댑터.
 *
 * <p>{@code Authorization} 헤더의 JWT 서명을 LiveKit apiKey/secret으로 검증한다. 서명 불일치·파싱 실패는
 * {@link InvalidWebhookException}으로 변환해 위조 요청을 거부한다. 검증된 이벤트는 SDK 타입을 감춘
 * {@link LiveWebhookEvent}로 변환해 반환한다.
 */
@Component
@RequiredArgsConstructor
public class LiveKitWebhookVerifier implements WebhookVerifier {

    // 본문 상한은 컨트롤러의 bounded read가 강제한다(chunked 포함). 여기서는 포트가 직접 호출되는 경우까지
    // 대비한 방어적 상한이며, 이것이 유일한 메모리 DoS 방어는 아니다.
    private static final int MAX_BODY_BYTES = 64 * 1024;

    private final WebhookReceiver webhookReceiver;

    @Override
    public LiveWebhookEvent verify(byte[] body, String authHeader) {
        if (authHeader == null || authHeader.isBlank()) {
            throw new InvalidWebhookException();
        }
        if (body == null || body.length == 0 || body.length > MAX_BODY_BYTES) {
            throw new InvalidWebhookException();
        }
        try {
            // 원본 바이트를 UTF-8로 명시 변환 — SDK가 UTF-8 기준으로 body sha256을 계산하므로 charset 불일치 방지.
            String payload = new String(body, StandardCharsets.UTF_8);
            WebhookEvent event = webhookReceiver.receive(payload, authHeader);
            return new LiveWebhookEvent(
                    event.getEvent(),
                    resolveRoomName(event),
                    event.hasIngressInfo() ? event.getIngressInfo().getIngressId() : null,
                    event.hasEgressInfo() ? event.getEgressInfo().getEgressId() : null
            );
        } catch (Exception e) {
            // 서명 불일치·파싱 실패 등 — 원본 값은 담지 않고 도메인 예외로 변환.
            throw new InvalidWebhookException(e);
        }
    }

    /**
     * 이벤트가 가리키는 방 이름을 뽑는다. room 이벤트(room_finished 등)는 {@code room}에,
     * ingress 이벤트(ingress_started 등)는 {@code ingressInfo.roomName}에 담기므로 room 우선·ingress fallback.
     * (proto 기본값은 빈 문자열이라 blank 는 미설정으로 간주.)
     */
    private String resolveRoomName(WebhookEvent event) {
        if (event.hasRoom() && !event.getRoom().getName().isBlank()) {
            return event.getRoom().getName();
        }
        if (event.hasIngressInfo() && !event.getIngressInfo().getRoomName().isBlank()) {
            return event.getIngressInfo().getRoomName();
        }
        return null;
    }
}
