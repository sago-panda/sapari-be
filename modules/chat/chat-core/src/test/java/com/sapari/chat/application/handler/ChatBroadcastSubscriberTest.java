package com.sapari.chat.application.handler;

import com.sapari.chat.domain.rule.ChatPermissionPolicy;
import org.mockito.Spy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sapari.chat.application.port.ChatMetrics;
import com.sapari.chat.application.port.ChatBroadcaster;
import com.sapari.chat.application.port.ChatSessionManager;
import com.sapari.chat.application.protocol.ChatEnvelope;
import com.sapari.chat.application.protocol.OutboundMessage;
import com.sapari.chat.domain.model.ChatMessage;
import com.sapari.chat.domain.model.ChatMessageType;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.model.ChatSession;
import com.sapari.global.time.TimeProvider;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class ChatBroadcastSubscriberTest {


    /** 반응형 종결자(.block() / StepVerifier) 상한 — 없으면 회귀가 테스트를 실패시키는 대신 <b>멈추게</b> 한다(근거: modules/chat/AGENTS.md). */
    private static final Duration VERIFY_CEILING = Duration.ofSeconds(10);
    @Mock
    private ChatBroadcaster broadcaster;

    @Mock
    private ChatSessionManager sessionManager;

    /**
     * 순수 정책은 가짜로 바꾸지 않는다 — 목으로 두면 노출 판정이 검증 대상에서 빠지고, 이 테스트가
     * 지키려는 것이 정확히 그 판정이다. {@code @Spy}라 실제 구현이 그대로 돈다.
     */
    @Spy
    private ChatPermissionPolicy permissionPolicy = new ChatPermissionPolicy();

    /**
     * 관측 포트는 목이 아니라 NOOP 이다 — 이 테스트가 보는 것은 라우팅 생존이고, 지표 호출을
     * 검증 대상에 넣으면 그 관심사가 섞인다. 지표는 {@code SubscriberLogContextTest} 와
     * 어댑터 테스트가 따로 지킨다.
     *
     * <p>{@code @Spy} 로 두는 이유: {@code @InjectMocks} 는 생성자 인자를 타입으로 찾으므로
     * 필드가 없으면 <b>null 을 넣고 조용히 통과</b>한다 — 그러면 fail-open 람다 안에서 NPE 가 나고
     * 그 NPE 는 그 람다를 통째로 깨서 "흡수가 침묵이 된" 상태를 만든다(실제로 그렇게 깨졌다).
     */
    @Spy
    private ChatMetrics metrics = ChatMetrics.NOOP;

    /** 전파 시간 칸이 실제 실행 시간을 재야 하므로 고정 시계가 아니라 시스템 시계다. */
    @Spy
    private TimeProvider timeProvider = new TimeProvider(Clock.systemUTC());

    @InjectMocks
    private ChatBroadcastSubscriber subscriber;

    private final UUID roomId = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private final UUID sellerId = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private final UUID buyerId = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Function<ChatSession, OutboundMessage>> resolverCaptor() {
        return ArgumentCaptor.forClass(Function.class);
    }

    private ChatSession owner() {
        return new ChatSession(roomId, sellerId, ChatRole.SELLER, "셀러", "seller@example.com", true);
    }

    private ChatSession viewer(UUID userId) {
        return new ChatSession(roomId, userId, ChatRole.BUYER, "구매자", "buyer@example.com", false);
    }

    /** 관리자는 소유와 무관하다 — 남의 방에 들어와도 owner=false다(그 조합만 유효하다). */
    private ChatSession admin() {
        return new ChatSession(roomId, UUID.randomUUID(), ChatRole.ADMIN, "관리자", "admin@sapari.com", false);
    }

    /** 남의 방에 시청자로 들어온 판매자 — 계정 role은 SELLER지만 이 방에서는 소유가 없다. */
    private ChatSession visitingSeller() {
        return new ChatSession(roomId, UUID.randomUUID(), ChatRole.SELLER, "방문셀러", "other@example.com", false);
    }

    @Test
    @DisplayName("⭐ 같은 뷰의 세션에는 같은 인스턴스를 돌려준다 — 전송 계층이 이걸로 직렬화를 묶는다")
    void theResolverReturnsTheSameInstancePerView() {
        // given
        given(sessionManager.sendToRoomGated(eq(roomId), any(Function.class))).willReturn(Mono.empty());
        ChatMessage message = new ChatMessage(
                "65a1f2c3d4e5f60718293a4b", roomId, buyerId, "구매자", "buyer@example.com",
                ChatRole.BUYER, new ChatMessageType.Normal(),
                "원본바보", "원본**", null, Instant.parse("2026-06-11T00:00:00Z"));

        // when
        StepVerifier.create(subscriber.route(roomId, new ChatEnvelope.ChatMsg(message)))
                .expectComplete().verify(VERIFY_CEILING);
        ArgumentCaptor<Function<ChatSession, OutboundMessage>> cap = resolverCaptor();
        then(sessionManager).should(times(1)).sendToRoomGated(eq(roomId), cap.capture());
        Function<ChatSession, OutboundMessage> resolver = cap.getValue();

        // then: 전송 계층({@code ChatSessionManager.sendToRoomGated} 계약)이 서로 다른 메시지를
        // <b>객체 동일성</b>으로 묶어 직렬화·인코딩을 종류 수만큼만 한다. 그래서 같은 뷰의 두 세션이
        // 같은 인스턴스를 받아야 한다 — 세션마다 new 로 만들면 내용이 같아도 묶이지 않고, 팬아웃
        // 비용이 다시 세션 수에 비례한다(실측: 12,000 세션 한 메시지에 직렬화 9.23ms + 인코딩 2.09ms).
        //
        // 그 회귀는 <b>조용하다</b> — 응답 내용이 같으니 어떤 내용 단언도 깨지지 않는다. 그래서
        // 동치(isEqualTo)가 아니라 동일성(isSameAs)으로 재야 한다.
        // 축이 <b>둘</b>이다 — 특권 여부 × 발신자 여부. "같은 뷰" 는 둘 다 같아야 한다.
        // (이 테스트를 처음 쓸 때 viewer(buyerId) 와 랜덤 id 를 같은 뷰로 봤는데, 전자는 발신자라
        //  senderView·후자는 normalView 다. 코드가 아니라 단언이 틀렸다.)
        assertThat(resolver.apply(viewer(UUID.randomUUID())))
                .as("같은 뷰(비특권·비발신자)의 두 세션이 서로 다른 인스턴스를 받았다 — "
                        + "팬아웃 비용이 세션 수에 비례한다")
                .isSameAs(resolver.apply(viewer(UUID.randomUUID())));
        assertThat(resolver.apply(owner()))
                .as("같은 뷰(특권·비발신자)인 방 주인과 관리자가 인스턴스를 공유하지 않았다")
                .isSameAs(resolver.apply(admin()));

        // 그리고 축이 다르면 인스턴스도 달라야 한다 — 같으면 마스킹이나 clientMsgId 가 섞인다.
        assertThat(resolver.apply(owner()))
                .as("특권 뷰와 일반 뷰가 같은 인스턴스다 — 마스킹이 섞인다")
                .isNotSameAs(resolver.apply(viewer(UUID.randomUUID())));
        assertThat(resolver.apply(viewer(buyerId)))
                .as("발신자 뷰와 일반 뷰가 같은 인스턴스다 — 남의 clientMsgId 가 실린다")
                .isNotSameAs(resolver.apply(viewer(UUID.randomUUID())));
    }

    @Test
    @DisplayName("CHAT 팬아웃: 방주인·관리자에겐 email·원문을, 시청자와 방문 판매자에겐 마스킹 본문만")
    void routeChat_Success() {
        // given
        given(sessionManager.sendToRoomGated(eq(roomId), any(Function.class))).willReturn(Mono.empty());
        ChatMessage message = new ChatMessage(
                "65a1f2c3d4e5f60718293a4b", roomId, buyerId, "구매자", "buyer@example.com",
                ChatRole.BUYER, new ChatMessageType.Normal(),
                "원본바보",          // originalMessage
                "원본**",           // displayMessage (마스킹)
                null, Instant.parse("2026-06-11T00:00:00Z"));

        // when
        StepVerifier.create(subscriber.route(roomId, new ChatEnvelope.ChatMsg(message))).expectComplete().verify(VERIFY_CEILING);

        // then
        ArgumentCaptor<Function<ChatSession, OutboundMessage>> cap = resolverCaptor();
        then(sessionManager).should(times(1)).sendToRoomGated(eq(roomId), cap.capture());
        Function<ChatSession, OutboundMessage> resolver = cap.getValue();

        OutboundMessage ownerMsg = resolver.apply(owner());
        assertThat(ownerMsg.type()).isEqualTo("NORMAL");
        assertThat(ownerMsg.senderEmail()).isEqualTo("buyer@example.com");
        assertThat(ownerMsg.originalMessage()).isEqualTo("원본바보");
        assertThat(ownerMsg.displayMessage()).isEqualTo("원본**");

        OutboundMessage viewerMsg = resolver.apply(viewer(buyerId));
        assertThat(viewerMsg.senderEmail()).isNull();          // PII 제외
        assertThat(viewerMsg.originalMessage()).isNull();      // 원문 제외
        assertThat(viewerMsg.displayMessage()).isEqualTo("원본**");   // 마스킹 본문은 유지

        // ⭐ 관리자도 방 주인과 같은 것을 본다 — 소유가 아니라 계정 권한으로 열린다.
        //    무엇을 강퇴할지 판단하려면 무슨 말을 했는지를 봐야 한다.
        OutboundMessage adminMsg = resolver.apply(admin());
        assertThat(adminMsg.senderEmail()).isEqualTo("buyer@example.com");
        assertThat(adminMsg.originalMessage()).isEqualTo("원본바보");

        // ⭐ 남의 방에 들어온 판매자는 여전히 못 본다 — 소유 기반 게이팅을 도입한 이유가 이것이고,
        //    관리자를 여는 것이 그 결정을 되돌리는 게 아님을 여기서 고정한다.
        OutboundMessage visitorMsg = resolver.apply(visitingSeller());
        assertThat(visitorMsg.senderEmail()).isNull();
        assertThat(visitorMsg.originalMessage()).isNull();
    }

    /**
     * 전파 시간이 <b>실행</b>을 재는지 — 조립을 재면 0 에 가깝게 나와 병목이 안 보인다. 세션에 넣는 일이
     * 구독 시점에 20ms 를 쓰게 두고, 기록이 그보다 짧으면 실행 전에 시계를 멈춘 것이다.
     */
    @Test
    @DisplayName("채팅 봉투 1건의 전파 시간을 기록한다 — 세션에 넣는 실행 시간까지 포함한다")
    void routeChat_recordsFanOutDurationIncludingExecution() {
        // given: 세션에 넣는 일이 실행될 때 20ms 걸린다
        given(sessionManager.sendToRoomGated(eq(roomId), any(Function.class)))
                .willReturn(Mono.fromRunnable(() -> spin(Duration.ofMillis(20))));
        ChatMessage message = new ChatMessage(
                "65a1f2c3d4e5f60718293a4b", roomId, buyerId, "구매자", "buyer@example.com",
                ChatRole.BUYER, new ChatMessageType.Normal(), "안녕", "안녕",
                null, Instant.parse("2026-06-11T00:00:00Z"));

        // when
        StepVerifier.create(subscriber.route(roomId, new ChatEnvelope.ChatMsg(message)))
                .expectComplete().verify(VERIFY_CEILING);

        // then
        ArgumentCaptor<Duration> took = ArgumentCaptor.forClass(Duration.class);
        then(metrics).should(times(1)).roomFanOutCompleted(took.capture());
        assertThat(took.getValue()).isGreaterThanOrEqualTo(Duration.ofMillis(20));
    }

    @Test
    @DisplayName("강퇴 봉투는 전파 시간에 넣지 않는다 — 세션 close 까지 포함돼 채팅 전파의 분포를 흐린다")
    void routeKick_doesNotRecordFanOutDuration() {
        // given
        given(sessionManager.sendToRoomGated(eq(roomId), any(Function.class))).willReturn(Mono.empty());
        given(sessionManager.closeUser(roomId, buyerId)).willReturn(Mono.empty());

        // when
        StepVerifier.create(subscriber.route(roomId, new ChatEnvelope.KickEvent(buyerId)))
                .expectComplete().verify(VERIFY_CEILING);

        // then
        then(metrics).should(never()).roomFanOutCompleted(any());
    }

    private static void spin(Duration duration) {
        long deadline = System.nanoTime() + duration.toNanos();
        while (System.nanoTime() - deadline < 0) {
            Thread.onSpinWait();
        }
    }

    @Test
    @DisplayName("KICK_EVENT 팬아웃 성공: 당사자에겐 SYSTEM(KICKED)을, 나머지에겐 KICK(userId)을 보내고 당사자 세션을 닫는다")
    void routeKickEvent_Success() {
        // given
        UUID kickedId = buyerId;
        UUID otherId = UUID.fromString("66666666-6666-6666-6666-666666666666");
        given(sessionManager.sendToRoomGated(eq(roomId), any(Function.class))).willReturn(Mono.empty());
        given(sessionManager.closeUser(roomId, kickedId)).willReturn(Mono.empty());

        // when
        StepVerifier.create(subscriber.route(roomId, new ChatEnvelope.KickEvent(kickedId))).expectComplete().verify(VERIFY_CEILING);

        // then
        then(sessionManager).should(times(1)).closeUser(roomId, kickedId);     // 당사자 세션 close

        ArgumentCaptor<Function<ChatSession, OutboundMessage>> cap = resolverCaptor();
        then(sessionManager).should(times(1)).sendToRoomGated(eq(roomId), cap.capture());
        Function<ChatSession, OutboundMessage> resolver = cap.getValue();

        OutboundMessage selfMsg = resolver.apply(viewer(kickedId));
        assertThat(selfMsg.type()).isEqualTo("SYSTEM");
        assertThat(selfMsg.code()).isEqualTo("KICKED");

        OutboundMessage otherMsg = resolver.apply(viewer(otherId));
        assertThat(otherMsg.type()).isEqualTo("KICK");
        assertThat(otherMsg.userId()).isEqualTo(kickedId);
    }

    @Test
    @DisplayName("CHAT 팬아웃 — 보낸 사람 세션에만 clientMsgId를 싣는다(자기 버블 짝짓기 키)")
    void routeChat_carriesClientMsgIdOnlyToSender() {
        // given
        given(sessionManager.sendToRoomGated(eq(roomId), any(Function.class))).willReturn(Mono.empty());
        ChatMessage message = new ChatMessage(
                "65a1f2c3d4e5f60718293a4b", roomId, buyerId, "구매자", "buyer@example.com",
                ChatRole.BUYER, new ChatMessageType.Normal(),
                "안녕", "안녕", "c-1", Instant.parse("2026-06-11T00:00:00Z"));

        // when
        StepVerifier.create(subscriber.route(roomId, new ChatEnvelope.ChatMsg(message))).expectComplete().verify(VERIFY_CEILING);

        // then
        ArgumentCaptor<Function<ChatSession, OutboundMessage>> cap = resolverCaptor();
        then(sessionManager).should(times(1)).sendToRoomGated(eq(roomId), cap.capture());
        Function<ChatSession, OutboundMessage> resolver = cap.getValue();

        // 보낸 사람 — ACK보다 브로드캐스트가 먼저 와도 이 키로 자기 버블을 찾는다
        assertThat(resolver.apply(viewer(buyerId)).clientMsgId()).isEqualTo("c-1");
        // 남 — 남의 클라 생성 id를 받을 이유가 없다
        assertThat(resolver.apply(viewer(sellerId)).clientMsgId()).isNull();
        // 방주인은 남의 메시지에도 PII를 받지만 clientMsgId는 자기 것만 받는다(두 축이 독립)
        OutboundMessage ownerOnOthers = resolver.apply(owner());
        assertThat(ownerOnOthers.senderEmail()).isEqualTo("buyer@example.com");
        assertThat(ownerOnOthers.clientMsgId()).isNull();
    }

    @Test
    @DisplayName("CHAT 팬아웃 — 방주인이 직접 보낸 경우 PII와 clientMsgId가 함께 실린다(두 축 동시 적용)")
    void routeChat_ownerSendingOwnMessage() {
        // given
        given(sessionManager.sendToRoomGated(eq(roomId), any(Function.class))).willReturn(Mono.empty());
        ChatMessage fromOwner = new ChatMessage(
                "65a1f2c3d4e5f60718293a4c", roomId, sellerId, "셀러", "seller@example.com",
                ChatRole.SELLER, new ChatMessageType.Normal(),
                "상품 설명", "상품 설명", "c-2", Instant.parse("2026-06-11T00:00:00Z"));

        // when
        StepVerifier.create(subscriber.route(roomId, new ChatEnvelope.ChatMsg(fromOwner))).expectComplete().verify(VERIFY_CEILING);

        // then
        ArgumentCaptor<Function<ChatSession, OutboundMessage>> cap = resolverCaptor();
        then(sessionManager).should(times(1)).sendToRoomGated(eq(roomId), cap.capture());

        OutboundMessage self = cap.getValue().apply(owner());
        assertThat(self.clientMsgId()).isEqualTo("c-2");
        assertThat(self.senderEmail()).isEqualTo("seller@example.com");
    }

    @Test
    @DisplayName("봉투 하나의 라우팅이 실패해도 방 구독은 살아 다음 봉투를 계속 받는다")
    void subscribeRoom_survivesPoisonEnvelope() {
        // given: 첫 봉투는 세션 전달에서 터지고, 둘째는 정상
        ChatMessage first = normal("첫 번째");
        ChatMessage second = normal("두 번째");
        given(broadcaster.subscribe(roomId))
                .willReturn(Flux.just(new ChatEnvelope.ChatMsg(first), new ChatEnvelope.ChatMsg(second)));
        given(sessionManager.sendToRoomGated(eq(roomId), any()))
                .willReturn(Mono.error(new RuntimeException("전달 실패")))
                .willReturn(Mono.empty());

        // when: 여기서 스트림이 죽으면 그 방은 이후 어떤 메시지도 못 받는다 — 조용한 전면 장애다
        Disposable subscription = subscriber.subscribeRoom(roomId);

        // then
        then(sessionManager).should(times(2)).sendToRoomGated(eq(roomId), any());
        assertThat(subscription).isNotNull();
    }

    @Test
    @DisplayName("구독 해제가 업스트림까지 전파된다 — 마지막 퇴장에서 회수되지 않으면 방마다 스트림이 쌓인다")
    void subscribeRoom_disposePropagatesUpstream() {
        // given: dispose()가 Disposable에서만 참인 건 Reactor의 성질이지 이 코드의 성질이 아니다.
        // 실제로 업스트림 구독이 끊기는지를 본다.
        AtomicBoolean cancelled = new AtomicBoolean();
        given(broadcaster.subscribe(roomId))
                .willReturn(Flux.<ChatEnvelope>never().doOnCancel(() -> cancelled.set(true)));

        // when
        Disposable subscription = subscriber.subscribeRoom(roomId);
        assertThat(cancelled).isFalse();
        subscription.dispose();

        // then
        assertThat(cancelled).isTrue();
    }

    private ChatMessage normal(String content) {
        return new ChatMessage("65a1f2c3d4e5f60718293a4b", roomId, buyerId, "구매자", "buyer@example.com",
                ChatRole.BUYER, new ChatMessageType.Normal(), content, content, null,
                Instant.parse("2026-06-11T00:00:00Z"));
    }
}
