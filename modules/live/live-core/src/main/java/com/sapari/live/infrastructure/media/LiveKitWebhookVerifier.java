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

    // 정상 LiveKit webhook 본문은 수 KB 수준 — 이를 크게 넘으면 비정상/공격으로 보고 검증 전에 거부한다.
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
                    event.hasRoom() ? event.getRoom().getName() : null,
                    event.hasIngressInfo() ? event.getIngressInfo().getIngressId() : null,
                    event.hasEgressInfo() ? event.getEgressInfo().getEgressId() : null
            );
        } catch (Exception e) {
            // 서명 불일치·파싱 실패 등 — 원본 값은 담지 않고 도메인 예외로 변환.
            throw new InvalidWebhookException(e);
        }
    }
}
