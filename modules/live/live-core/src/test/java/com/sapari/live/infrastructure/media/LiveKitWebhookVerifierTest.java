package com.sapari.live.infrastructure.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import io.livekit.server.WebhookReceiver;
import livekit.LivekitEgress.EgressInfo;
import livekit.LivekitModels.Room;
import livekit.LivekitWebhook.WebhookEvent;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.live.application.port.LiveWebhookEvent;
import com.sapari.live.domain.exception.InvalidWebhookException;

class LiveKitWebhookVerifierTest {

    private final WebhookReceiver receiver = mock(WebhookReceiver.class);
    private final LiveKitWebhookVerifier verifier = new LiveKitWebhookVerifier(receiver);

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("검증된 WebhookEvent를 도메인 이벤트로 변환한다(room_finished → roomName 채움)")
    void mapsRoomFinishedEvent() {
        WebhookEvent event = WebhookEvent.newBuilder()
                .setEvent("room_finished")
                .setRoom(Room.newBuilder().setName("room-42").build())
                .build();
        given(receiver.receive("body", "Bearer sig")).willReturn(event);

        LiveWebhookEvent result = verifier.verify(bytes("body"), "Bearer sig");

        assertThat(result.type()).isEqualTo("room_finished");
        assertThat(result.roomName()).isEqualTo("room-42");
        assertThat(result.ingressId()).isNull();
        assertThat(result.egressId()).isNull();
    }

    @Test
    @DisplayName("egress 이벤트는 egressId를 채운다")
    void mapsEgressEvent() {
        WebhookEvent event = WebhookEvent.newBuilder()
                .setEvent("egress_ended")
                .setEgressInfo(EgressInfo.newBuilder().setEgressId("eg-1").build())
                .build();
        given(receiver.receive("body", "Bearer sig")).willReturn(event);

        LiveWebhookEvent result = verifier.verify(bytes("body"), "Bearer sig");

        assertThat(result.type()).isEqualTo("egress_ended");
        assertThat(result.egressId()).isEqualTo("eg-1");
        assertThat(result.roomName()).isNull();
    }

    @Test
    @DisplayName("한글(비ASCII) 본문도 UTF-8 바이트 그대로 SDK에 전달한다(charset 불일치 방지)")
    void passesUtf8BodyUnchanged() {
        WebhookEvent event = WebhookEvent.newBuilder().setEvent("room_started")
                .setRoom(Room.newBuilder().setName("방-42").build()).build();
        given(receiver.receive("{\"room\":\"방-42\"}", "Bearer sig")).willReturn(event);

        LiveWebhookEvent result = verifier.verify(bytes("{\"room\":\"방-42\"}"), "Bearer sig");

        assertThat(result.roomName()).isEqualTo("방-42");
    }

    @Test
    @DisplayName("서명 검증 실패는 InvalidWebhookException으로 변환한다")
    void wrapsVerificationFailure() {
        given(receiver.receive("body", "bad")).willThrow(new RuntimeException("signature mismatch"));

        assertThatThrownBy(() -> verifier.verify(bytes("body"), "bad"))
                .isInstanceOf(InvalidWebhookException.class);
    }

    @Test
    @DisplayName("Authorization 헤더가 없으면(null/blank) 검증 없이 거부한다")
    void rejectsMissingAuthHeader() {
        assertThatThrownBy(() -> verifier.verify(bytes("body"), null))
                .isInstanceOf(InvalidWebhookException.class);
        assertThatThrownBy(() -> verifier.verify(bytes("body"), "  "))
                .isInstanceOf(InvalidWebhookException.class);
    }

    @Test
    @DisplayName("본문이 비었거나 크기 상한(64KB)을 넘으면 검증 전에 거부한다")
    void rejectsEmptyOrOversizedBody() {
        assertThatThrownBy(() -> verifier.verify(new byte[0], "Bearer sig"))
                .isInstanceOf(InvalidWebhookException.class);
        assertThatThrownBy(() -> verifier.verify(new byte[64 * 1024 + 1], "Bearer sig"))
                .isInstanceOf(InvalidWebhookException.class);
    }
}
