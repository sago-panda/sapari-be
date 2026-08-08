package com.sapari.chat.application.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

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

import com.sapari.chat.application.port.ChatBroadcaster;
import com.sapari.chat.application.port.ChatSessionManager;
import com.sapari.chat.application.protocol.ChatEnvelope;
import com.sapari.chat.application.protocol.OutboundMessage;
import com.sapari.chat.domain.model.ChatMessage;
import com.sapari.chat.domain.model.ChatMessageType;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.model.ChatSession;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class ChatBroadcastSubscriberTest {

    @Mock
    private ChatBroadcaster broadcaster;

    @Mock
    private ChatSessionManager sessionManager;

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

    @Test
    @DisplayName("CHAT 팬아웃 성공: 방주인 세션이면 email·원문을 싣고, 일반 세션이면 마스킹 본문만 싣는다")
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
        StepVerifier.create(subscriber.route(roomId, new ChatEnvelope.ChatMsg(message))).verifyComplete();

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
        StepVerifier.create(subscriber.route(roomId, new ChatEnvelope.KickEvent(kickedId))).verifyComplete();

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
        StepVerifier.create(subscriber.route(roomId, new ChatEnvelope.ChatMsg(message))).verifyComplete();

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
        StepVerifier.create(subscriber.route(roomId, new ChatEnvelope.ChatMsg(fromOwner))).verifyComplete();

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
