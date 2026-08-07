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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.web.reactive.socket.CloseStatus;

import com.sapari.chat.application.protocol.OutboundMessage;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.model.ChatSession;
import com.sapari.chat.domain.repository.ChatSessionRepository;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
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
    @DisplayName("등록 — Redis 명부 등재가 실패해도 접속은 성립한다(전달은 로컬 자료구조로 돈다)")
    void register_survives_redis_failure() {
        when(sessionRepository.add(any(), any(), any())).thenReturn(Mono.error(new RuntimeException("redis down")));

        StepVerifier.create(registry.register("s1", session(roomId, userId))).verifyComplete();

        // 명부 등재와 무관하게 방 fan-out은 로컬 인덱스로 도달한다
        StepVerifier.create(registry.outbound("s1"))
                .then(() -> registry.sendToRoomLocal(roomId, out("SYSTEM")).block())
                .expectNextCount(1)
                .thenCancel()
                .verify();
    }

    @Test
    @DisplayName("퇴장 — Redis 명부 제거가 실패해도 정리는 완료된다(호출부가 subscribe라 에러가 새면 안 됨)")
    void unregister_survives_redis_failure() {
        registry.register("s1", session(roomId, userId)).block();
        when(sessionRepository.remove(any(), any())).thenReturn(Mono.error(new RuntimeException("redis down")));

        StepVerifier.create(registry.unregister(roomId, "s1")).verifyComplete();

        // 로컬 정리는 Redis와 무관하게 끝나 있어야 한다
        StepVerifier.create(registry.outbound("s1")).verifyComplete();
        assertThat(registry.trackedRoomCount()).isZero();
    }

    @Test
    @DisplayName("강퇴 — 버퍼가 막혀 데이터 채널로 못 닫아도 제어 채널로 종료되고 사유는 1008")
    void kick_terminates_through_control_channel_when_buffer_is_blocked() {
        // given: 소켓을 읽지 않는 클라(request 0). 버퍼는 넘치지 않게 채워 overflow 종료와 섞이지 않게 한다.
        registry.register("s1", session(roomId, userId)).block();
        StepVerifier.create(registry.outbound("s1"), 0)
                .then(() -> {
                    for (int i = 0; i < 5; i++) {
                        registry.sendToSession("s1", out("NORMAL")).block();
                    }
                    registry.closeUser(roomId, userId).block();   // when
                })
                // 데이터 채널로는 아무것도 못 간다 — complete도 쌓인 5건 뒤에 막혀 전달되지 않는다
                .expectNoEvent(Duration.ofMillis(50))
                .thenCancel()
                .verify();

        // then: 그럼에도 제어 신호는 발화하고, 종료 사유가 1008로 남는다
        StepVerifier.create(registry.terminationSignal("s1"))
                .expectNext(CloseStatus.POLICY_VIOLATION)
                .verifyComplete();
        assertThat(registry.closeStatusOf("s1")).isEqualTo(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    @DisplayName("방 종료 — 정상 종료(1000)로 닫는다. 사유는 앞서 보낸 SYSTEM이 전달")
    void room_end_closes_normally() {
        registry.register("s1", session(roomId, userId)).block();
        // 종료 전에는 사유가 미정이다 — closeStatusOf의 기본값(NORMAL)만 보면 closeAll을 지워도 통과한다
        assertThat(registry.isTerminating("s1")).isFalse();

        registry.closeAll(roomId).block();

        assertThat(registry.isTerminating("s1")).isTrue();
        StepVerifier.create(registry.terminationSignal("s1"))
                .expectNext(CloseStatus.NORMAL)
                .verifyComplete();
    }

    @Test
    @DisplayName("제어 신호 — 모르는 세션은 영영 발화하지 않는다(빈 Mono면 멀쩡한 세션이 즉시 닫힌다)")
    void termination_signal_never_fires_for_unknown_session() {
        StepVerifier.create(registry.terminationSignal("없는세션"))
                .expectSubscription()
                .expectNoEvent(Duration.ofMillis(50))
                .thenCancel()
                .verify();
    }

    @ParameterizedTest
    @EnumSource(value = Sinks.EmitResult.class, names = { "FAIL_OVERFLOW", "FAIL_ZERO_SUBSCRIBER" })
    @DisplayName("종료 판정 — 버퍼가 차서 못 담은 경우만 세션 종료 대상")
    void emit_results_that_mean_slow_consumer(Sinks.EmitResult result) {
        assertThat(registry.consumerCannotKeepUp(result)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = Sinks.EmitResult.class,
            names = { "OK", "FAIL_TERMINATED", "FAIL_CANCELLED", "FAIL_NON_SERIALIZED" })
    @DisplayName("종료 판정 — 일시 경합은 세션이 멀쩡하므로 종료 대상이 아니다")
    void emit_results_that_must_not_close_session(Sinks.EmitResult result) {
        // FAIL_NON_SERIALIZED로 끊으면 부하가 오를수록 멀쩡한 시청자를 끊고, 재접속이 부하를 더 올린다
        assertThat(registry.consumerCannotKeepUp(result)).isFalse();
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

        // then: 조용히 사라진 메시지가 없다 — 도착했거나, 드롭으로 세어졌거나 둘 중 하나.
        // (CPU가 모자란 러너에선 재시도가 소진돼 일부가 드롭될 수 있고, 그건 계수되므로 정상이다.
        //  수정 전에는 이 경합 실패가 아무 데도 안 잡혀 그대로 사라졌다.)
        assertThat(received.size() + registry.droppedOnContention())
                .isEqualTo((long) threads * perThread);
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

    @Test
    @DisplayName("파싱 불가 프레임 — 상한 전까지는 세션을 살려두고, 넘으면 1008로 끊는다")
    void malformed_frames_close_session_only_after_limit() {
        registry.register("s1", session(roomId, userId)).block();

        // 상한 직전까지: 계속 응답하라(false)고 답하고 세션도 살아 있다
        for (int i = 1; i < ChatSessionRegistry.MALFORMED_FRAME_LIMIT; i++) {
            assertThat(registry.recordMalformedFrame("s1")).isFalse();
        }
        assertThat(registry.isTerminating("s1")).isFalse();

        // 상한에 닿는 순간: 응답하지 말라(true) + 정책성 종료로 확정
        assertThat(registry.recordMalformedFrame("s1")).isTrue();
        assertThat(registry.isTerminating("s1")).isTrue();
        assertThat(registry.closeStatusOf("s1")).isEqualTo(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    @DisplayName("파싱 불가 프레임 — 이미 사라진 세션이면 응답하지 않는다")
    void malformed_frame_on_unknown_session_is_not_answered() {
        assertThat(registry.recordMalformedFrame("없는세션")).isTrue();
    }
}
