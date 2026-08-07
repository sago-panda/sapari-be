package com.sapari.live.infrastructure.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import io.livekit.server.WebhookReceiver;
import livekit.LivekitEgress.EgressInfo;
import livekit.LivekitIngress.IngressInfo;
import livekit.LivekitModels.Room;
import livekit.LivekitWebhook.WebhookEvent;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.global.time.TimeProvider;
import com.sapari.live.application.port.LiveWebhookEvent;
import com.sapari.live.domain.exception.InvalidWebhookException;

class LiveKitWebhookVerifierTest {

    private static final Instant NOW = Instant.parse("2026-06-10T12:00:00Z");

    private final WebhookReceiver receiver = mock(WebhookReceiver.class);
    private final LiveKitWebhookVerifier verifier =
            new LiveKitWebhookVerifier(receiver, new TimeProvider(Clock.fixed(NOW, ZoneOffset.UTC)));

    /** 재전송 방어가 createdAt 을 요구하므로 모든 이벤트에 발생 시각을 넣는다. */
    private static WebhookEvent.Builder freshEvent(String type) {
        return WebhookEvent.newBuilder().setEvent(type).setCreatedAt(NOW.getEpochSecond());
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("검증된 WebhookEvent를 도메인 이벤트로 변환한다(room_finished → roomName 채움)")
    void mapsRoomFinishedEvent() {
        WebhookEvent event = freshEvent("room_finished")
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
        WebhookEvent event = freshEvent("egress_ended")
                .setEgressInfo(EgressInfo.newBuilder().setEgressId("eg-1").build())
                .build();
        given(receiver.receive("body", "Bearer sig")).willReturn(event);

        LiveWebhookEvent result = verifier.verify(bytes("body"), "Bearer sig");

        assertThat(result.type()).isEqualTo("egress_ended");
        assertThat(result.egressId()).isEqualTo("eg-1");
        assertThat(result.roomName()).isNull();
    }

    @Test
    @DisplayName("ingress 이벤트는 room 이 비어도 ingressInfo.roomName 으로 roomName·ingressId 를 채운다")
    void mapsIngressEventFromIngressInfo() {
        WebhookEvent event = freshEvent("ingress_started")
                .setIngressInfo(IngressInfo.newBuilder()
                        .setIngressId("ing-1")
                        .setRoomName("room-42")
                        .build())
                .build();
        given(receiver.receive("body", "Bearer sig")).willReturn(event);

        LiveWebhookEvent result = verifier.verify(bytes("body"), "Bearer sig");

        assertThat(result.type()).isEqualTo("ingress_started");
        assertThat(result.roomName()).isEqualTo("room-42");
        assertThat(result.ingressId()).isEqualTo("ing-1");
    }

    @Test
    @DisplayName("한글(비ASCII) 본문도 UTF-8 바이트 그대로 SDK에 전달한다(charset 불일치 방지)")
    void passesUtf8BodyUnchanged() {
        WebhookEvent event = freshEvent("room_started")
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

    @Test
    @DisplayName("과거 창을 벗어난 오래된 이벤트는 거부한다 — 서명만으로는 재전송을 막지 못한다")
    void rejectsStaleEvent() {
        WebhookEvent stale = WebhookEvent.newBuilder()
                .setEvent("ingress_started")
                .setCreatedAt(NOW.minusSeconds(15 * 60 + 1).getEpochSecond())
                .build();
        given(receiver.receive("body", "Bearer sig")).willReturn(stale);

        assertThatThrownBy(() -> verifier.verify(bytes("body"), "Bearer sig"))
                .isInstanceOf(InvalidWebhookException.class);
    }

    @Test
    @DisplayName("창 안의 재전송은 통과한다 — 재전송은 원본 createdAt 을 유지하므로 배포 지연을 견뎌야 한다")
    void acceptsRetryWithinWindow() {
        WebhookEvent retried = WebhookEvent.newBuilder()
                .setEvent("ingress_started")
                .setCreatedAt(NOW.minusSeconds(14 * 60).getEpochSecond())
                .setIngressInfo(IngressInfo.newBuilder().setIngressId("ing-1").build())
                .build();
        given(receiver.receive("body", "Bearer sig")).willReturn(retried);

        assertThat(verifier.verify(bytes("body"), "Bearer sig").ingressId()).isEqualTo("ing-1");
    }

    @Test
    @DisplayName("허용치를 넘는 미래 시각은 거부한다 — 시계가 어긋난 발신자거나 위조")
    void rejectsFutureEvent() {
        WebhookEvent future = WebhookEvent.newBuilder()
                .setEvent("ingress_started")
                .setCreatedAt(NOW.plusSeconds(61).getEpochSecond())
                .build();
        given(receiver.receive("body", "Bearer sig")).willReturn(future);

        assertThatThrownBy(() -> verifier.verify(bytes("body"), "Bearer sig"))
                .isInstanceOf(InvalidWebhookException.class);
    }

    @Test
    @DisplayName("경계: 정확히 과거 창 끝·미래 허용치 끝은 통과한다")
    void acceptsExactBoundaries() {
        WebhookEvent atPastEdge = WebhookEvent.newBuilder().setEvent("ingress_started")
                .setCreatedAt(NOW.minusSeconds(15 * 60).getEpochSecond()).build();
        given(receiver.receive("past", "Bearer sig")).willReturn(atPastEdge);
        assertThat(verifier.verify(bytes("past"), "Bearer sig").type()).isEqualTo("ingress_started");

        WebhookEvent atFutureEdge = WebhookEvent.newBuilder().setEvent("ingress_started")
                .setCreatedAt(NOW.plusSeconds(60).getEpochSecond()).build();
        given(receiver.receive("future", "Bearer sig")).willReturn(atFutureEdge);
        assertThat(verifier.verify(bytes("future"), "Bearer sig").type()).isEqualTo("ingress_started");
    }

    @Test
    @DisplayName("미래 허용치는 과거 창보다 훨씬 좁다 — 넓히면 공격 가능 구간만 늘어난다")
    void futureToleranceIsMuchTighterThanPastWindow() {
        WebhookEvent fiveMinutesAhead = WebhookEvent.newBuilder().setEvent("ingress_started")
                .setCreatedAt(NOW.plusSeconds(5 * 60).getEpochSecond()).build();
        given(receiver.receive("body", "Bearer sig")).willReturn(fiveMinutesAhead);

        // 같은 5분이라도 과거는 통과, 미래는 거부여야 한다
        assertThatThrownBy(() -> verifier.verify(bytes("body"), "Bearer sig"))
                .isInstanceOf(InvalidWebhookException.class);
    }

    @Test
    @DisplayName("createdAt 이 없으면 거부한다 — 판정 못 하는데 통과시키면 통제가 있는 척만 한다")
    void rejectsEventWithoutCreatedAt() {
        WebhookEvent noTimestamp = WebhookEvent.newBuilder().setEvent("ingress_started").build();
        given(receiver.receive("body", "Bearer sig")).willReturn(noTimestamp);

        assertThatThrownBy(() -> verifier.verify(bytes("body"), "Bearer sig"))
                .isInstanceOf(InvalidWebhookException.class);
    }
}
