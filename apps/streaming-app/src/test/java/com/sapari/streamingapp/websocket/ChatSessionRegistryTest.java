package com.sapari.streamingapp.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.chat.application.protocol.OutboundMessage;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.model.ChatSession;
import com.sapari.chat.domain.repository.ChatSessionRepository;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ChatSessionRegistryTest {

    private ChatSessionRepository sessionRepository;
    private ChatSessionRegistry registry;

    private final UUID roomId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        sessionRepository = mock(ChatSessionRepository.class);
        when(sessionRepository.add(any(), any(), any())).thenReturn(Mono.empty());
        when(sessionRepository.remove(any(), any())).thenReturn(Mono.empty());
        registry = new ChatSessionRegistry(sessionRepository);
    }

    private ChatSession session(UUID room, UUID user) {
        return new ChatSession(room, user, ChatRole.BUYER, "닉", "e@example.com", false);
    }

    private OutboundMessage out(String type) {
        return new OutboundMessage(type, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("register — Redis HSET 위임 + outbound로 받은 메시지가 흘러나온다")
    void register_then_sendToSession_flows_out() {
        registry.register("s1", session(roomId, userId)).block();
        verify(sessionRepository).add(roomId, "s1", userId);

        OutboundMessage msg = out("NORMAL");
        StepVerifier.create(registry.outbound("s1"))
                .then(() -> registry.sendToSession("s1", msg).block())
                .expectNext(msg)
                .thenCancel()
                .verify();
    }

    @Test
    @DisplayName("sendToRoomLocal — 같은 방 세션은 받는다")
    void room_local_delivers_to_same_room() {
        registry.register("s1", session(roomId, userId)).block();

        OutboundMessage msg = out("SYSTEM");
        StepVerifier.create(registry.outbound("s1"))
                .then(() -> registry.sendToRoomLocal(roomId, msg).block())
                .expectNext(msg)
                .thenCancel()
                .verify();
    }

    @Test
    @DisplayName("sendToRoomLocal — 다른 방 세션은 안 받는다")
    void room_local_skips_other_room() {
        registry.register("s1", session(roomId, userId)).block();

        StepVerifier.create(registry.outbound("s1"))
                .then(() -> registry.sendToRoomLocal(UUID.randomUUID(), out("SYSTEM")).block())
                .expectNoEvent(Duration.ofMillis(50))
                .thenCancel()
                .verify();
    }

    @Test
    @DisplayName("getActiveCount — Redis count(고유 유저 수)로 위임")
    void active_count_delegates() {
        when(sessionRepository.count(roomId)).thenReturn(Mono.just(5L));

        StepVerifier.create(registry.getActiveCount(roomId))
                .expectNext(5L)
                .verifyComplete();
    }

    @Test
    @DisplayName("unregister — Redis remove 위임 + 로컬에서 사라져 outbound가 빈 스트림")
    void unregister_removes_local_and_redis() {
        registry.register("s1", session(roomId, userId)).block();

        registry.unregister(roomId, "s1").block();
        verify(sessionRepository).remove(roomId, "s1");

        // 제거 후 outbound("s1")은 빈 Flux(즉시 완료)
        StepVerifier.create(registry.outbound("s1")).verifyComplete();
    }

    @Test
    @DisplayName("closeAll — 방 세션의 아웃바운드가 완료(complete)된다")
    void close_all_completes_outbound() {
        registry.register("s1", session(roomId, userId)).block();

        StepVerifier.create(registry.outbound("s1"))
                .then(() -> registry.closeAll(roomId).block())
                .verifyComplete();
    }

    @Test
    @DisplayName("closeUser — 해당 유저 세션의 아웃바운드만 완료")
    void close_user_completes_target_outbound() {
        registry.register("s1", session(roomId, userId)).block();

        StepVerifier.create(registry.outbound("s1"))
                .then(() -> registry.closeUser(roomId, userId).block())
                .verifyComplete();
        assertThat(registry.outbound("unknown")).isNotNull();
    }

    @Test
    @DisplayName("방 인덱스 — 마지막 세션이 나가면 방 항목까지 제거된다(인덱스 누수 방지)")
    void room_index_drops_room_when_last_session_leaves() {
        // given: 같은 방에 세션 2개
        registry.register("s1", session(roomId, userId)).block();
        registry.register("s2", session(roomId, UUID.randomUUID())).block();
        assertThat(registry.trackedRoomCount()).isEqualTo(1);

        // when: 하나만 퇴장
        registry.unregister(roomId, "s1").block();

        // then: 아직 s2가 있으므로 방은 유지
        assertThat(registry.trackedRoomCount()).isEqualTo(1);

        // when: 마지막 세션도 퇴장
        registry.unregister(roomId, "s2").block();

        // then: 방 항목이 남지 않는다 — 남기면 방송할 때마다 죽은 방이 쌓인다
        assertThat(registry.trackedRoomCount()).isZero();
    }

    @Test
    @DisplayName("sendToRoomGated — 같은 방 세션마다 resolver 결과를 받고, 다른 방은 받지 않는다")
    void gated_fanout_is_per_session_and_room_scoped() {
        UUID otherRoom = UUID.randomUUID();
        registry.register("s1", session(roomId, userId)).block();
        registry.register("s2", session(roomId, UUID.randomUUID())).block();
        registry.register("other", session(otherRoom, UUID.randomUUID())).block();

        // when: resolver가 세션마다 다른 메시지를 낸다(방주인 PII 게이팅이 쓰는 방식)
        registry.sendToRoomGated(roomId, s -> out(s.userId().equals(userId) ? "OWNER" : "PLAIN")).block();

        // then: 같은 방 세션은 각자 몫을 받고
        StepVerifier.create(registry.outbound("s1"))
                .expectNextMatches(m -> "OWNER".equals(m.type()))
                .thenCancel()
                .verify();
        StepVerifier.create(registry.outbound("s2"))
                .expectNextMatches(m -> "PLAIN".equals(m.type()))
                .thenCancel()
                .verify();

        // 다른 방 세션은 아무것도 못 받는다
        StepVerifier.create(registry.outbound("other"))
                .expectSubscription()
                .expectNoEvent(Duration.ofMillis(50))
                .thenCancel()
                .verify();
    }

    @Test
    @DisplayName("동시 emit — 여러 스레드가 같은 세션에 보내도 유실되지 않는다")
    void concurrent_emit_does_not_drop_messages() throws Exception {
        // given: 구독 중인 세션 하나. 실제로도 ack(WS 이벤트루프)와 브로드캐스트(Redis pubsub 스레드)가
        // 같은 세션 Sink에 동시 emit한다 — unicast Sink는 경합 시 FAIL_NON_SERIALIZED로 값을 버린다.
        registry.register("s1", session(roomId, userId)).block();
        List<OutboundMessage> received = new CopyOnWriteArrayList<>();
        registry.outbound("s1").subscribe(received::add);

        int threads = 4;
        int perThread = 500;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        // when
        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        registry.sendToSession("s1", out("NORMAL")).block();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();

        // then
        assertThat(received).hasSize(threads * perThread);
    }

    @Test
    @DisplayName("아웃바운드 버퍼 초과 — 느린 클라 세션을 끊는다(Pod 힙 보호)")
    void outbound_overflow_closes_session() {
        // given: 구독하되 소비하지 않는(request 0) 느린 클라
        registry.register("s1", session(roomId, userId)).block();

        // when: 버퍼 용량의 2배를 밀어넣는다
        // then: 버퍼 크기만큼만 담기고, 무제한 적체 대신 스트림이 종료된다(= 세션 끊김)
        StepVerifier.create(registry.outbound("s1"), 0)
                .then(() -> {
                    for (int i = 0; i < ChatSessionRegistry.OUTBOUND_BUFFER_SIZE * 2; i++) {
                        registry.sendToSession("s1", out("NORMAL")).block();
                    }
                })
                .thenRequest(Long.MAX_VALUE)   // 실제로는 session.send()가 request한다
                .expectNextCount(ChatSessionRegistry.OUTBOUND_BUFFER_SIZE)
                .verifyComplete();
    }
}
