package com.sapari.chat.application.handler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sapari.chat.application.port.ChatAccountEventSource;
import com.sapari.chat.application.port.ChatSessionManager;
import com.sapari.chat.application.protocol.ChatAccountEvent;
import com.sapari.chat.application.protocol.OutboundMessage;
import com.sapari.chat.application.protocol.SystemMessageCode;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChatAccountEventHandler — 조용히 앉아 있는 세션을 끊는다")
class ChatAccountEventHandlerTest {

    @Mock
    private ChatAccountEventSource source;

    @Mock
    private ChatSessionManager sessionManager;

    private final UUID userId = UUID.randomUUID();

    private ChatAccountEventHandler handler() {
        return new ChatAccountEventHandler(source, sessionManager);
    }

    @Test
    @DisplayName("⭐ 사유를 먼저 보내고 그 다음에 끊는다 — 순서가 뒤집히면 클라가 이유를 모른 채 1008만 받는다")
    void sendsTheReasonBeforeClosing() {
        // given
        given(sessionManager.sendToUserEverywhere(eq(userId), any())).willReturn(Mono.empty());
        given(sessionManager.closeUserEverywhere(userId)).willReturn(Mono.empty());

        // when
        StepVerifier.create(handler().onEvent(new ChatAccountEvent.Banned(userId))).verifyComplete();

        // then: 종료가 먼저 가면 sink가 닫혀 뒤이은 사유 프레임이 조용히 사라진다
        InOrder order = Mockito.inOrder(sessionManager);
        order.verify(sessionManager).sendToUserEverywhere(eq(userId),
                eq(OutboundMessage.system(SystemMessageCode.BANNED)));
        order.verify(sessionManager).closeUserEverywhere(userId);
    }

    @Test
    @DisplayName("⭐ 사유 전송이 실패해도 끊는다 — 알림 하나 때문에 밴된 사람이 남는 것이 최악이다")
    void closesEvenWhenTheReasonCannotBeDelivered() {
        // given
        given(sessionManager.sendToUserEverywhere(eq(userId), any()))
                .willReturn(Mono.error(new IllegalStateException("전송 실패")));
        given(sessionManager.closeUserEverywhere(userId)).willReturn(Mono.empty());

        // when & then: 실패가 종료를 막지 않는다
        StepVerifier.create(handler().onEvent(new ChatAccountEvent.Banned(userId))).verifyComplete();
        then(sessionManager).should().closeUserEverywhere(userId);
    }

    @Test
    @DisplayName("이벤트 하나가 터져도 구독은 살아 있다 — 죽으면 이 Pod는 재시작까지 모든 조치를 놓친다")
    void oneBadEventDoesNotKillTheSubscription() {
        // given: 첫 이벤트 처리가 실패하고 두 번째는 정상이다
        UUID second = UUID.randomUUID();
        given(source.events()).willReturn(reactor.core.publisher.Flux.just(
                new ChatAccountEvent.Banned(userId), new ChatAccountEvent.Banned(second)));
        given(sessionManager.sendToUserEverywhere(eq(userId), any()))
                .willThrow(new IllegalStateException("조립 시점 throw"));
        given(sessionManager.sendToUserEverywhere(eq(second), any())).willReturn(Mono.empty());
        given(sessionManager.closeUserEverywhere(second)).willReturn(Mono.empty());

        // when
        handler().start();

        // then: 두 번째가 처리됐다 = 첫 번째의 실패가 스트림을 죽이지 않았다
        then(sessionManager).should().closeUserEverywhere(second);
        then(sessionManager).should(never()).closeUserEverywhere(userId);
    }
}
