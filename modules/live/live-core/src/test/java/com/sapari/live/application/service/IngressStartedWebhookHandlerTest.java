package com.sapari.live.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.live.application.port.PromotionTrigger;
import com.sapari.live.application.port.LiveWebhookEvent;

class IngressStartedWebhookHandlerTest {

    private final GoLiveByRtmpService goLiveByRtmpService = mock(GoLiveByRtmpService.class);
    private final IngressStartedWebhookHandler handler = new IngressStartedWebhookHandler(goLiveByRtmpService);

    @Test
    @DisplayName("ingress_started 만 supports")
    void supportsOnlyIngressStarted() {
        assertThat(handler.supports("ingress_started")).isTrue();
        assertThat(handler.supports("room_finished")).isFalse();
        assertThat(handler.supports("egress_ended")).isFalse();
    }

    @Test
    @DisplayName("roomName(UUID)을 파싱해 GoLiveByRtmpService 로 위임한다")
    void delegatesWithParsedRoomId() {
        UUID roomId = UUID.randomUUID();
        LiveWebhookEvent event = new LiveWebhookEvent("ingress_started", roomId.toString(), "ing-1", null);

        handler.handle(event);

        then(goLiveByRtmpService).should().goLiveByRtmp(roomId, "ing-1", PromotionTrigger.WEBHOOK);
    }

    @Test
    @DisplayName("roomName 이 없으면 위임하지 않는다")
    void skipsWhenRoomNameMissing() {
        LiveWebhookEvent event = new LiveWebhookEvent("ingress_started", null, "ing-1", null);

        handler.handle(event);

        then(goLiveByRtmpService).should(never()).goLiveByRtmp(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("roomName 이 UUID 형식이 아니면 위임하지 않는다")
    void skipsWhenRoomNameNotUuid() {
        LiveWebhookEvent event = new LiveWebhookEvent("ingress_started", "not-a-uuid", "ing-1", null);

        handler.handle(event);

        then(goLiveByRtmpService).should(never()).goLiveByRtmp(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
