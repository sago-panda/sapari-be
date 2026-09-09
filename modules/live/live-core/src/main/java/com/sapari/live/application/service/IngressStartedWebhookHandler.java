package com.sapari.live.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.sapari.live.application.port.LiveWebhookEvent;
import com.sapari.live.application.port.LiveWebhookHandler;
import com.sapari.live.application.port.PromotionTrigger;

/**
 * {@code ingress_started} webhook → RTMP go-live 전이 트리거. OBS 가 ingress 에 연결되면 LiveKit 이 이 이벤트를
 * 보내고, roomName(=roomId)으로 방을 찾아 {@link GoLiveByRtmpService}에 전이를 위임한다.
 *
 * <p>얇은 트리거 어댑터 — 비즈니스 로직·멱등성은 서비스가 책임진다. roomName 이 없거나 UUID 형식이 아니면
 * 스킵한다(잘못된 이벤트로 전이를 시도하지 않음). {@code ingressId} 는 그대로 넘겨 서비스가 방의
 * {@code ingress_id} 와 대조한다 — 방 이름만으로 전이하면 그 방의 것이 아닌 ingress 도 방을 Live 로 올린다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IngressStartedWebhookHandler implements LiveWebhookHandler {

    private static final String EVENT_TYPE = "ingress_started";

    private final GoLiveByRtmpService goLiveByRtmpService;

    @Override
    public boolean supports(String eventType) {
        return EVENT_TYPE.equals(eventType);
    }

    @Override
    public void handle(LiveWebhookEvent event) {
        UUID roomId = parseRoomId(event.roomName());
        if (roomId == null) {
            return;
        }
        goLiveByRtmpService.goLiveByRtmp(roomId, event.ingressId(), PromotionTrigger.WEBHOOK);
    }

    private UUID parseRoomId(String roomName) {
        if (roomName == null || roomName.isBlank()) {
            log.warn("ingress_started webhook 에 roomName 이 없어 전이 스킵");
            return null;
        }
        try {
            return UUID.fromString(roomName);
        } catch (IllegalArgumentException e) {
            log.warn("ingress_started webhook 의 roomName 이 UUID 형식이 아님 — 전이 스킵: roomName={}", roomName);
            return null;
        }
    }
}
