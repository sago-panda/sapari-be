package com.sapari.live.application.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.live.application.port.LiveWebhookEvent;
import com.sapari.live.application.port.LiveWebhookHandler;
import com.sapari.live.application.port.WebhookVerifier;
import com.sapari.live.command.LiveWebhookCommand;
import com.sapari.live.domain.exception.InvalidWebhookException;

class LiveWebhookServiceTest {

    private final WebhookVerifier verifier = mock(WebhookVerifier.class);

    private static final byte[] BODY = "raw-body".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private LiveWebhookCommand command() {
        return new LiveWebhookCommand(BODY, "Bearer sig");
    }

    @Test
    @DisplayName("검증된 이벤트를 supports가 true인 핸들러에만 디스패치한다")
    void routesToMatchingHandler() {
        LiveWebhookEvent event = new LiveWebhookEvent("room_finished", "room-1", null, null);
        given(verifier.verify(BODY, "Bearer sig")).willReturn(event);

        LiveWebhookHandler matching = mock(LiveWebhookHandler.class);
        given(matching.supports("room_finished")).willReturn(true);
        LiveWebhookHandler other = mock(LiveWebhookHandler.class);
        given(other.supports("room_finished")).willReturn(false);

        LiveWebhookService service = new LiveWebhookService(verifier, List.of(matching, other));
        service.process(command());

        then(matching).should().handle(event);
        then(other).should(never()).handle(event);
    }

    @Test
    @DisplayName("한 핸들러가 예외를 던져도 격리되어 다른 핸들러는 실행되고 예외가 위로 전파되지 않는다")
    void isolatesHandlerFailure() {
        LiveWebhookEvent event = new LiveWebhookEvent("room_finished", "room-1", null, null);
        given(verifier.verify(BODY, "Bearer sig")).willReturn(event);

        LiveWebhookHandler failing = mock(LiveWebhookHandler.class);
        given(failing.supports("room_finished")).willReturn(true);
        org.mockito.BDDMockito.willThrow(new RuntimeException("boom")).given(failing).handle(event);
        LiveWebhookHandler healthy = mock(LiveWebhookHandler.class);
        given(healthy.supports("room_finished")).willReturn(true);

        LiveWebhookService service = new LiveWebhookService(verifier, List.of(failing, healthy));

        // 예외가 밖으로 던져지지 않음(LiveKit 전체 재시도 방지)
        service.process(command());
        // 실패한 핸들러 뒤의 핸들러도 실행됨
        then(healthy).should().handle(event);
    }

    @Test
    @DisplayName("매칭 핸들러가 없으면 검증만 하고 아무 것도 처리하지 않는다(no-op)")
    void noopWhenNoHandlerMatches() {
        LiveWebhookEvent event = new LiveWebhookEvent("track_published", "room-1", null, null);
        given(verifier.verify(BODY, "Bearer sig")).willReturn(event);

        LiveWebhookHandler handler = mock(LiveWebhookHandler.class);
        given(handler.supports("track_published")).willReturn(false);

        LiveWebhookService service = new LiveWebhookService(verifier, List.of(handler));
        service.process(command());

        then(handler).should(never()).handle(event);
    }

    @Test
    @DisplayName("핸들러가 하나도 없어도(구현 미배선) 정상 통과한다")
    void noopWhenNoHandlersRegistered() {
        LiveWebhookEvent event = new LiveWebhookEvent("egress_ended", null, null, "eg-1");
        given(verifier.verify(BODY, "Bearer sig")).willReturn(event);

        LiveWebhookService service = new LiveWebhookService(verifier, List.of());

        // 예외 없이 통과
        service.process(command());
    }

    @Test
    @DisplayName("서명 검증 실패(InvalidWebhookException)는 라우팅 없이 그대로 전파된다")
    void propagatesVerificationFailure() {
        given(verifier.verify(BODY, "Bearer sig"))
                .willThrow(new InvalidWebhookException(new RuntimeException("bad sig")));

        LiveWebhookHandler handler = mock(LiveWebhookHandler.class);
        LiveWebhookService service = new LiveWebhookService(verifier, List.of(handler));

        assertThatThrownBy(() -> service.process(command()))
                .isInstanceOf(InvalidWebhookException.class);
        then(handler).should(never()).handle(org.mockito.ArgumentMatchers.any());
    }
}
