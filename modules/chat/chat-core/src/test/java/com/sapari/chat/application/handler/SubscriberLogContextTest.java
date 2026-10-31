package com.sapari.chat.application.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.sapari.chat.application.port.ChatBroadcaster;
import com.sapari.chat.application.port.ChatSessionManager;
import com.sapari.chat.application.protocol.ChatEnvelope;
import com.sapari.chat.domain.model.ChatMessage;
import com.sapari.chat.domain.model.ChatMessageType;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.rule.ChatPermissionPolicy;
import com.sapari.global.logging.LogFields;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ThreadLocalAccessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Redis 구독 스레드에서 도는 로그가 <b>어느 방인지</b> 들고 나가는지 본다.
 *
 * <p>이 자리는 세션 컨텍스트가 없다 — 팬아웃은 Pod 공유 구독 스레드에서 돌고, 그 실패는 그 방
 * <b>전원</b>이 그 메시지를 못 받았다는 뜻이다. 방 단위로 묶이지 않으면 "한 방의 문제인가 전체인가" 를
 * 가를 수 없는데, 하필 그 구분이 장애 대응의 첫 갈림길이다.
 *
 * <p>로그 문장에 {@code roomId={}} 가 이미 있었다. 그건 사람이 읽을 때만 쓸모가 있고, 필드가 아니라
 * 메시지 안 문자열이라 집계가 안 된다 — 이 테스트가 보는 것은 그 값이 <b>MDC</b>에 있는지다.
 */
@DisplayName("구독 스레드 로그 컨텍스트 — 팬아웃 실패가 room_id 를 들고 나간다")
class SubscriberLogContextTest {

    /** 반응형 종결자(.block() / StepVerifier) 상한 — 없으면 회귀가 테스트를 실패시키는 대신 <b>멈추게</b> 한다(근거: modules/chat/AGENTS.md). */
    private static final Duration VERIFY_CEILING = Duration.ofSeconds(10);

    private final UUID roomId = UUID.randomUUID();

    private Logger subscriberLogger;
    private ListAppender<ILoggingEvent> appender;

    /**
     * MDC 접근자와 자동 전파를 켠다.
     *
     * <p>운영에서는 호스트 앱(streaming-app)이 한다 — 이 셋을 쓰는 앱이 그것뿐이다(live-app 은
     * 발행만 하고 {@code com.sapari.chat} 을 스캔에서 뺀다). 모듈 테스트에서는 그 배선을 흉내 낸다.
     */
    @BeforeAll
    static void enablePropagation() {
        ContextRegistry.getInstance().registerThreadLocalAccessor(new ThreadLocalAccessor<String>() {
            @Override public Object key() { return LogFields.ROOM_ID; }
            @Override public String getValue() { return org.slf4j.MDC.get(LogFields.ROOM_ID); }
            @Override public void setValue(String value) { org.slf4j.MDC.put(LogFields.ROOM_ID, value); }
            @Override public void setValue() { org.slf4j.MDC.remove(LogFields.ROOM_ID); }
        });
        Hooks.enableAutomaticContextPropagation();
    }

    @BeforeEach
    void setUp() {
        subscriberLogger = (Logger) LoggerFactory.getLogger(ChatBroadcastSubscriber.class);
        appender = new ListAppender<>();
        appender.start();
        subscriberLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        subscriberLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("⭐ 라우팅 실패 로그에 room_id 가 붙는다 — 한 방의 문제인지 전체인지를 가르는 축이다")
    void theFanOutFailureCarriesTheRoomId() {
        // given: 그 방을 구독하면 봉투가 하나 오고, 라우팅이 실패한다
        ChatBroadcaster broadcaster = mock(ChatBroadcaster.class);
        given(broadcaster.subscribe(roomId)).willReturn(
                Flux.just((ChatEnvelope) new ChatEnvelope.ChatMsg(message()))
                        .subscribeOn(Schedulers.boundedElastic()));
        ChatSessionManager sessionManager = mock(ChatSessionManager.class);
        given(sessionManager.sendToRoomGated(any(), any()))
                .willReturn(Mono.error(new IllegalStateException("팬아웃 실패")));

        ChatBroadcastSubscriber subscriber =
                new ChatBroadcastSubscriber(broadcaster, sessionManager, new ChatPermissionPolicy());

        // when
        subscriber.subscribeRoom(roomId);
        await(() -> !appender.list.isEmpty());

        // then
        assertThat(appender.list).as("라우팅 실패 로그가 남지 않았다").isNotEmpty();
        assertThat(appender.list.get(0).getMDCPropertyMap())
                .as("팬아웃 실패가 어느 방인지 없이 나갔다")
                .containsEntry(LogFields.ROOM_ID, roomId.toString());
    }

    private ChatMessage message() {
        return ChatMessage.builder()
                .roomId(roomId)
                .senderId(UUID.randomUUID())
                .senderNickname("닉")
                .senderEmail("e@example.com")
                .senderRole(ChatRole.BUYER)
                .type(new ChatMessageType.Normal())
                .originalMessage("안녕")
                .displayMessage("안녕")
                .clientMsgId("c-1")
                .createdAt(java.time.Instant.parse("2026-06-18T00:00:00Z"))
                .build();
    }

    /** 구독이 다른 스레드에서 도므로 짧게 기다린다 — 상한을 둬서 회귀가 멈춤이 되지 않게 한다. */
    private static void await(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + VERIFY_CEILING.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() - deadline < 0) {
            Thread.onSpinWait();
        }
    }
}
