package com.sapari.chat.application.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sapari.chat.application.port.ChatAccountEventSource;
import com.sapari.chat.application.port.ChatSessionManager;
import com.sapari.chat.application.port.SessionCutReason;
import com.sapari.chat.application.protocol.ChatAccountEvent;
import com.sapari.chat.application.protocol.OutboundMessage;
import com.sapari.chat.application.protocol.SystemMessageCode;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChatAccountEventHandler — 조용히 앉아 있는 세션을 끊는다")
class ChatAccountEventHandlerTest {


    /** 반응형 종결자(.block() / StepVerifier) 상한 — 없으면 회귀가 테스트를 실패시키는 대신 <b>멈추게</b> 한다(근거: modules/chat/AGENTS.md). */
    private static final Duration VERIFY_CEILING = Duration.ofSeconds(10);
    @Mock
    private ChatAccountEventSource source;

    @Mock
    private ChatSessionManager sessionManager;

    private final UUID userId = UUID.randomUUID();

    private ChatAccountEventHandler handler() {
        return new ChatAccountEventHandler(source, sessionManager);
    }

    /**
     * ⚠️ <b>{@code inOrder} 로 재지 않는다 — 그것은 호출 순서이고 계약은 실행 순서다.</b>
     * {@code .then(close(...))} 의 인자가 조립 시점에 평가되므로 Mockito 가 보는 호출 순서는
     * <b>소스 순서</b>다. 그래서 조립 순서를 그대로 둔 채 실행만 뒤집는 변이
     * ({@code close.then(reason)})가 {@code inOrder} 를 만족시키며 통과했다 — SPR-174 에서
     * 실측으로 확인했다(같은 모양이 탈퇴 핸들러 테스트에도 있었고 같이 고쳤다). 그때 아래 주석이
     * 적은 결과가 그대로 일어난다. 그래서 두 스텁이 <b>구독될 때</b> 이름을 적고 그 순서를 본다.
     */
    @Test
    @DisplayName("⭐ 사유를 먼저 보내고 그 다음에 끊는다 — 순서가 뒤집히면 클라가 이유를 모른 채 1008만 받는다")
    void sendsTheReasonBeforeClosing() {
        // given: 종료가 먼저 가면 sink가 닫혀 뒤이은 사유 프레임이 조용히 사라진다
        List<String> ran = new CopyOnWriteArrayList<>();
        given(sessionManager.sendToUserEverywhere(eq(userId), any()))
                .willReturn(Mono.fromRunnable(() -> ran.add("reason")));
        given(sessionManager.closeUserEverywhere(userId, SessionCutReason.MODERATION))
                .willReturn(Mono.fromRunnable(() -> ran.add("close")));

        // when
        StepVerifier.create(handler().onEvent(new ChatAccountEvent.Banned(userId))).expectComplete().verify(VERIFY_CEILING);

        // then
        assertThat(ran).as("실행 순서 — 호출 순서가 아니다").containsExactly("reason", "close");
        then(sessionManager).should().sendToUserEverywhere(eq(userId),
                eq(OutboundMessage.system(SystemMessageCode.BANNED)));
        then(sessionManager).should().closeUserEverywhere(userId, SessionCutReason.MODERATION);
    }

    @Test
    @DisplayName("⭐ 사유 전송이 실패해도 끊는다 — 알림 하나 때문에 밴된 사람이 남는 것이 최악이다")
    void closesEvenWhenTheReasonCannotBeDelivered() {
        // given
        given(sessionManager.sendToUserEverywhere(eq(userId), any()))
                .willReturn(Mono.error(new IllegalStateException("전송 실패")));
        given(sessionManager.closeUserEverywhere(userId, SessionCutReason.MODERATION)).willReturn(Mono.empty());

        // when & then: 실패가 종료를 막지 않는다
        StepVerifier.create(handler().onEvent(new ChatAccountEvent.Banned(userId))).expectComplete().verify(VERIFY_CEILING);
        then(sessionManager).should().closeUserEverywhere(userId, SessionCutReason.MODERATION);
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
        given(sessionManager.closeUserEverywhere(second, SessionCutReason.MODERATION)).willReturn(Mono.empty());

        // when
        handler().start();

        // then: 두 번째가 처리됐다 = 첫 번째의 실패가 스트림을 죽이지 않았다
        then(sessionManager).should().closeUserEverywhere(second, SessionCutReason.MODERATION);
        then(sessionManager).should(never()).closeUserEverywhere(userId, SessionCutReason.MODERATION);
    }
}
