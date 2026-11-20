package com.sapari.streamingapp.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.chat.application.port.ChatMetrics;
import org.springframework.core.io.buffer.DataBufferUtils;

import com.sapari.chat.application.protocol.OutboundMessage;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.model.ChatSession;
import com.sapari.chat.domain.repository.ChatSessionRepository;
import com.sapari.chat.domain.rule.ChatPermissionPolicy;

import io.netty.buffer.UnpooledByteBufAllocator;

import org.springframework.core.io.buffer.NettyDataBuffer;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;

import reactor.core.publisher.Mono;

/**
 * 팬아웃 1회의 <b>직렬화 횟수</b>를 고정한다 — 세션 수가 아니라 <b>종류 수</b>여야 한다.
 *
 * <p>직렬화는 세션별 아웃바운드 파이프라인에서 일어나므로, 공유 장치가 없으면 방에 N 세션이면
 * 같은 문자열을 N 번 만든다. 반면 서로 다른 payload 는 몇 종뿐이다
 * ({@code fanOutChat} 은 특권 × 발신자로 4종). <b>실측</b>: 직렬화 1회 0.77us →
 * 12,000 세션 한 메시지에 <b>9.23ms</b>, 4종이면 0.0031ms. 약 3,000 배이고 그 비용이
 * 이벤트루프 경로에서 난다.
 *
 * <p>그래서 이 파일이 재는 것은 "메시지가 도착했는가" 가 아니라 <b>"몇 번 만들었는가"</b> 다.
 * 전달은 다른 테스트들이 이미 덮고 있고, 여기 단언이 없으면 공유 장치를 떼도 전부 초록이다.
 */
@DisplayName("아웃바운드 프레임 공유 — 직렬화가 종류 수에 비례한다")
class OutboundFrameSharingTest {

    /** 반응형 종결자(.block() / StepVerifier) 상한 — 없으면 회귀가 테스트를 실패시키는 대신 <b>멈추게</b> 한다(근거: modules/chat/AGENTS.md). */
    private static final Duration VERIFY_CEILING = Duration.ofSeconds(10);

    private static final int SESSIONS = 10;

    private ChatSessionRegistry registry;
    private final UUID roomId = UUID.randomUUID();

    /** 핸들러가 하는 직렬화를 흉내 내면서 횟수를 센다. */
    private final AtomicInteger serializations = new AtomicInteger();
    private final Function<OutboundMessage, String> counting = message -> {
        serializations.incrementAndGet();
        return message.type() + ":" + message.activeCount();
    };

    @BeforeEach
    void setUp() {
        ChatSessionRepository sessionRepository = mock(ChatSessionRepository.class);
        lenient().when(sessionRepository.add(any(), any(), any())).thenReturn(Mono.empty());
        lenient().when(sessionRepository.heartbeatInterval()).thenReturn(Duration.ofMinutes(10));
        registry = new ChatSessionRegistry(sessionRepository, new ChatPermissionPolicy(), ChatMetrics.NOOP);
    }

    /** 세션 10개를 등록하고 각자의 프레임 흐름을 모은다 — 절반은 방 주인이라 뷰가 둘로 갈린다. */
    private List<OutboundFrame> registerAndCollect() {
        List<OutboundFrame> frames = new CopyOnWriteArrayList<>();
        for (int i = 0; i < SESSIONS; i++) {
            boolean owner = i % 2 == 0;
            String sid = "s" + i;
            registry.register(sid, new ChatSession(roomId, UUID.randomUUID(),
                            owner ? ChatRole.SELLER : ChatRole.BUYER, "닉", "e@example.com", owner))
                    .block(VERIFY_CEILING);
            registry.outboundFrames(sid).subscribe(frames::add);
        }
        return frames;
    }

    @Test
    @DisplayName("⭐ 뷰 둘을 10 세션에 뿌려도 직렬화는 2회다 — 공유가 없으면 10회다")
    void aFanOutSerializesOncePerDistinctView() {
        // given: 호출부가 뷰를 미리 만들어 재사용하는 모양(fanOutChat 과 같다)
        List<OutboundFrame> frames = registerAndCollect();
        OutboundMessage ownerView = OutboundMessage.roomInfo(7L, true);
        OutboundMessage viewerView = OutboundMessage.roomInfo(7L, false);

        // when
        registry.sendToRoomGated(roomId,
                session -> session.isRoomOwner() ? ownerView : viewerView).block(VERIFY_CEILING);
        // 핸들러가 세션마다 하는 일을 그대로 한다
        frames.forEach(frame -> frame.utf8(counting));

        // then
        assertThat(frames).as("열 세션이 각자 한 프레임을 받아야 한다").hasSize(SESSIONS);
        assertThat(serializations)
                .as("직렬화가 세션 수에 비례한다 — 같은 뷰를 받는 세션들이 프레임을 공유하지 않았다")
                .hasValue(2);

        // ⭐ 같은 뷰를 받는 세션들은 <b>같은 배열 인스턴스</b>를 받는다. 그게 이 변경의 하중 지점이다 —
        // 세션은 그 배열을 자기 버퍼로 감싸기만 하므로 UTF-8 인코딩도 종류 수만큼만 돈다.
        // 인스턴스 동일성으로 재는 이유: 내용 비교는 "따로 만들어 같은 값" 과 구분되지 않는다.
        //
        // 순서에 의존하지 않는다. 팬아웃이 ConcurrentHashMap 기반 인덱스를 순회하므로 frames 의
        // 순서는 해시에 달려 있다 — frames.get(0) 을 "방 주인 것" 으로 읽으면 세션 id 작명이나
        // 세트 크기가 바뀔 때 엉뚱하게 깨진다. 그래서 뷰별로 묶어서 센다.
        Map<OutboundMessage, Set<byte[]>> arraysByView = new IdentityHashMap<>();
        for (OutboundFrame frame : frames) {
            arraysByView
                    .computeIfAbsent(frame.message(),
                            view -> Collections.newSetFromMap(new IdentityHashMap<>()))
                    .add(frame.utf8(counting));
        }
        assertThat(arraysByView).as("뷰가 둘이어야 한다").hasSize(2);
        assertThat(arraysByView.values())
                .as("한 뷰를 받는 세션들이 서로 다른 배열을 받았다 — 공유가 안 됐다")
                .allSatisfy(arrays -> assertThat(arrays).hasSize(1));
        assertThat(serializations).as("이미 만들어 둔 것을 다시 만들었다").hasValue(2);
    }

    @Test
    @DisplayName("⭐ 전원이 같은 메시지면 직렬화는 1회다 — sendToRoomLocal 도 프레임을 공유한다")
    void aBroadcastToEveryoneSerializesOnce() {
        List<OutboundFrame> frames = registerAndCollect();

        registry.sendToRoomLocal(roomId, OutboundMessage.roomInfo(3L, false)).block(VERIFY_CEILING);
        frames.forEach(frame -> frame.utf8(counting));

        assertThat(frames).hasSize(SESSIONS);
        assertThat(serializations).hasValue(1);
    }

    @Test
    @DisplayName("같은 프레임을 두 번 읽어도 한 번만 만든다 — 지연 계산이 멱등이다")
    void readingTheSameFrameTwiceSerializesOnce() {
        OutboundFrame frame = new OutboundFrame(OutboundMessage.roomInfo(1L, false));

        byte[] first = frame.utf8(counting);
        byte[] second = frame.utf8(counting);

        assertThat(second).isSameAs(first);
        assertThat(serializations).hasValue(1);
    }

    @Test
    @DisplayName("아무도 소비하지 않은 프레임은 직렬화되지 않는다 — 끊긴 세션의 비용을 내지 않는다")
    void anUnconsumedFrameIsNeverSerialized() {
        // 구독하지 않은 세션에게만 보낸다 — 프레임은 버퍼에 남고 utf8 은 불리지 않는다
        registry.register("lonely", new ChatSession(roomId, UUID.randomUUID(),
                ChatRole.BUYER, "닉", "e@example.com", false)).block(VERIFY_CEILING);

        registry.sendToRoomLocal(roomId, OutboundMessage.roomInfo(1L, false)).block(VERIFY_CEILING);

        assertThat(serializations).hasValue(0);
    }

    // ---------- 핸들러 절반: 세션들이 같은 배열을 본다 ----------

    /**
     * 운영과 같은 버퍼 팩토리를 쓰는 세션 목.
     *
     * <p>{@code NettyDataBufferFactory} 여야 한다 — 운영 경로가 타입으로 그걸 강제한다
     * ({@code ReactorNettyRequestUpgradeStrategy} 가 {@code bufferFactory()} 뒤에
     * {@code NettyDataBufferFactory} 로 checkcast 하고, {@code ReactorNettyWebSocketSession} 생성자
     * 시그니처도 그 타입이다). {@code DefaultDataBufferFactory} 로 재면 이 티켓에서 이미 한 번 틀린
     * "어느 팩토리로 쟀나" 를 또 밟는다.
     */
    private static WebSocketSession sessionWithRealBufferFactory() {
        WebSocketSession session = mock(WebSocketSession.class);
        given(session.bufferFactory())
                .willReturn(new NettyDataBufferFactory(new UnpooledByteBufAllocator(false)));
        return session;
    }

    /**
     * 와이어 매핑만 재는 핸들러 — 나머지 협력자는 쓰이지 않으므로 목으로 둔다.
     *
     * <p>{@code toWireMessage} 가 쓰는 것은 {@code serialize}(= 자기 ObjectMapper)와 인자로 받은
     * 세션의 버퍼 팩토리뿐이다. 입장 흐름을 흉내 내지 않아도 그 자리를 밟을 수 있어서 이 테스트가
     * 성립한다.
     */
    private static ChatWebSocketHandler handlerForWireMapping() {
        return new ChatWebSocketHandler(
                mock(com.sapari.streamingapp.websocket.auth.RoomTokenVerifier.class),
                mock(EntryGate.class),
                mock(ChatSessionRegistry.class),
                mock(com.sapari.chat.port.SendChatUseCase.class),
                mock(com.sapari.chat.application.handler.ChatBroadcastSubscriber.class),
                mock(NettyConnectionRegistry.class),
                mock(com.sapari.chat.application.service.SystemMessageService.class),
                new SessionTimeouts(Duration.ofHours(1), Duration.ofSeconds(30), Duration.ofSeconds(90)),
                ChatMetrics.NOOP);
    }

    @Test
    @DisplayName("⭐ 같은 프레임을 받은 두 세션의 WS payload 가 같은 배열을 본다 — 3배 이득이 여기서 난다")
    void twoSessionsWrapTheSameArray() {
        // given: 한 팬아웃이 두 세션에 싣는 같은 프레임
        OutboundFrame shared = new OutboundFrame(OutboundMessage.roomInfo(7L, false));
        ChatWebSocketHandler handler = handlerForWireMapping();
        WebSocketSession first = sessionWithRealBufferFactory();
        WebSocketSession second = sessionWithRealBufferFactory();

        // when: 핸들러가 세션마다 하는 일
        WebSocketMessage one = handler.toWireMessage(first, shared);
        WebSocketMessage two = handler.toWireMessage(second, shared);

        // then: 배열을 공유한다. 내용 비교로는 "따로 인코딩해 같은 값" 과 구분되지 않으므로
        // 네이티브 버퍼의 배열 인스턴스를 본다.
        //
        // 되돌림이 잡히는 자리: 운영 등가 회귀(세션마다 bufferFactory().wrap(serialize(..)))로
        // 바꾸면 <b>이 공유 단언</b>에서 깨진다. 다만 session.textMessage(..) 로 되돌리면 목 세션에
        // 그 스텁이 없어 단언보다 먼저 NPE 로 죽는다 — 보장은 서지만 실패 지점이 다르다.
        byte[] firstArray = ((NettyDataBuffer) one.getPayload()).getNativeBuffer().array();
        byte[] secondArray = ((NettyDataBuffer) two.getPayload()).getNativeBuffer().array();
        assertThat(secondArray)
                .as("두 세션이 서로 다른 배열을 받았다 — 세션마다 인코딩한 것이다")
                .isSameAs(firstArray);

        // 프레임 모양도 함께 — TEXT 가 아니면 클라가 못 읽는다
        assertThat(one.getType()).isEqualTo(WebSocketMessage.Type.TEXT);
        assertThat(one.getPayloadAsText()).isEqualTo(two.getPayloadAsText());
    }

    @Test
    @DisplayName("세션마다 자기 버퍼를 받는다 — 같은 ByteBuf 를 공유하면 한쪽이 읽으면 다른 쪽이 빈다")
    void eachSessionGetsItsOwnBuffer() {
        OutboundFrame shared = new OutboundFrame(OutboundMessage.roomInfo(7L, false));
        ChatWebSocketHandler handler = handlerForWireMapping();

        WebSocketMessage one = handler.toWireMessage(sessionWithRealBufferFactory(), shared);
        WebSocketMessage two = handler.toWireMessage(sessionWithRealBufferFactory(), shared);

        // 배열은 공유하지만 버퍼(= readerIndex)는 따로여야 한다
        assertThat(two.getPayload()).isNotSameAs(one.getPayload());
        one.getPayloadAsText();
        assertThat(two.getPayloadAsText())
                .as("한쪽이 읽자 다른 쪽이 비었다 — 같은 ByteBuf 를 공유한 것이다")
                .isNotEmpty();
    }

    /**
     * ⭐ 한쪽 버퍼를 <b>해제한 뒤</b>에도 다른 쪽이 온전하다.
     *
     * <p>공유가 안전하다는 근거는 두 절반이다 — ① 동시 읽기가 서로 간섭하지 않는다 ② 한쪽 해제가
     * 배열을 건드리지 않는다({@code UnpooledHeapByteBuf.freeArray} 가 no-op). ①은 위 테스트들이
     * 밟는데 <b>②는 어떤 테스트도 밟지 않았다</b> — 해제 경로를 타는 케이스가 없었고, 그 절반이
     * 틀리면 교차 세션 payload 오염(특권 뷰 원문 포함)이라 가장 비싼 쪽이다. 바이트코드로 확인한
     * 사실을 동작으로도 고정한다.
     */
    @Test
    @DisplayName("⭐ 한쪽 버퍼를 해제해도 다른 쪽이 온전하다 — 틀리면 교차 세션 payload 오염이다")
    void releasingOneBufferLeavesTheOtherIntact() {
        OutboundFrame shared = new OutboundFrame(OutboundMessage.roomInfo(7L, false));
        ChatWebSocketHandler handler = handlerForWireMapping();

        WebSocketMessage one = handler.toWireMessage(sessionWithRealBufferFactory(), shared);
        WebSocketMessage two = handler.toWireMessage(sessionWithRealBufferFactory(), shared);
        String expected = two.getPayloadAsText();

        // 운영에서 Reactor Netty 가 전송 뒤에 하는 일 — 그 해제가 공유 배열을 건드리면 안 된다.
        DataBufferUtils.release(one.getPayload());

        WebSocketMessage third = handler.toWireMessage(sessionWithRealBufferFactory(), shared);
        assertThat(third.getPayloadAsText())
                .as("한쪽 해제가 공유 배열을 망가뜨렸다 — 뒤에 감싼 세션이 깨진 payload 를 받는다")
                .isEqualTo(expected);
    }
}
