package com.sapari.streamingapp.websocket;

import com.sapari.chat.domain.rule.ChatPermissionPolicy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
        given(sessionRepository.add(any(), any(), any())).willReturn(Mono.empty());
        given(sessionRepository.remove(any(), any())).willReturn(Mono.empty());
        registry = new ChatSessionRegistry(sessionRepository, new ChatPermissionPolicy());
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
        // given
        registry.register("s1", session(roomId, userId)).block();
        then(sessionRepository).should(times(1)).add(roomId, "s1", userId);

        OutboundMessage msg = out("NORMAL");

        // when & then
        StepVerifier.create(registry.outbound("s1"))
                .then(() -> registry.sendToSession("s1", msg).block())
                .expectNext(msg)
                .thenCancel()
                .verify();
    }

    @Test
    @DisplayName("sendToRoomLocal — 같은 방 세션은 받는다")
    void room_local_delivers_to_same_room() {
        // given
        registry.register("s1", session(roomId, userId)).block();

        OutboundMessage msg = out("SYSTEM");

        // when & then
        StepVerifier.create(registry.outbound("s1"))
                .then(() -> registry.sendToRoomLocal(roomId, msg).block())
                .expectNext(msg)
                .thenCancel()
                .verify();
    }

    @Test
    @DisplayName("sendToRoomLocal — 다른 방 세션은 안 받는다")
    void room_local_skips_other_room() {
        // given
        registry.register("s1", session(roomId, userId)).block();

        // when & then
        StepVerifier.create(registry.outbound("s1"))
                .then(() -> registry.sendToRoomLocal(UUID.randomUUID(), out("SYSTEM")).block())
                .expectNoEvent(Duration.ofMillis(50))
                .thenCancel()
                .verify();
    }

    @Test
    @DisplayName("getActiveCount — Redis count(고유 유저 수)로 위임")
    void active_count_delegates() {
        // given
        given(sessionRepository.count(roomId)).willReturn(Mono.just(5L));

        // when & then
        StepVerifier.create(registry.getActiveCount(roomId))
                .expectNext(5L)
                .expectComplete()
                .verify(java.time.Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("unregister — Redis remove 위임 + 로컬에서 사라져 outbound가 빈 스트림")
    void unregister_removes_local_and_redis() {
        // given
        registry.register("s1", session(roomId, userId)).block();

        registry.unregister(roomId, "s1").block();
        then(sessionRepository).should(times(1)).remove(roomId, "s1");

        // 제거 후 outbound("s1")은 빈 Flux(즉시 완료)

        // when & then
        StepVerifier.create(registry.outbound("s1")).expectComplete()
                .verify(java.time.Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("closeAll — 방 세션의 아웃바운드가 완료(complete)된다")
    void close_all_completes_outbound() {
        // given
        registry.register("s1", session(roomId, userId)).block();

        // when & then
        StepVerifier.create(registry.outbound("s1"))
                .then(() -> registry.closeAll(roomId).block())
                .expectComplete()
                .verify(java.time.Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("closeUser — 해당 유저 세션의 아웃바운드만 완료")
    void close_user_completes_target_outbound() {
        // given
        registry.register("s1", session(roomId, userId)).block();

        // when
        StepVerifier.create(registry.outbound("s1"))
                .then(() -> registry.closeUser(roomId, userId).block())
                .expectComplete()
                .verify(java.time.Duration.ofSeconds(5));

        // then
        assertThat(registry.outbound("unknown")).isNotNull();
    }

    @Test
    @DisplayName("⭐ closeUserEverywhere — 다른 방에 열려 있는 같은 사람의 세션도 끊는다")
    void close_user_everywhere_reaches_other_rooms() {
        // given: 한 사람이 두 방에 동시에 접속해 있다. 밴이 걸릴 때 다른 방 세션이 정확히 이 상태다.
        UUID otherRoom = UUID.randomUUID();
        registry.register("s1", session(roomId, userId)).block();
        registry.register("s2", session(otherRoom, userId)).block();

        // when & then: 방을 모르는 채로 끊어도 둘 다 닫힌다.
        // 방 색인으로 찾으면 강퇴가 일어난 방 하나만 닫히고 나머지는 살아남는다 — 그게 이 메서드가 없앤 상태다.
        //
        // 상한을 준다. 안 주면 닫히지 않은 세션에서 이 테스트가 <b>영원히 기다린다</b> — 회귀가 빨간불이
        // 아니라 멈춤으로 나타나고, 그건 회귀를 알려 주지 않는 것과 같다(되돌림 확인에서 실제로 겪었다).
        StepVerifier.create(registry.outbound("s2"))
                .then(() -> registry.closeUserEverywhere(userId).block())
                .expectComplete()
                .verify(java.time.Duration.ofSeconds(5));
        StepVerifier.create(registry.outbound("s1"))
                .expectComplete()
                .verify(java.time.Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("closeUserEverywhere — 남의 세션은 건드리지 않는다")
    void close_user_everywhere_spares_other_users() {
        // given
        UUID bystander = UUID.randomUUID();
        registry.register("s1", session(roomId, userId)).block();
        registry.register("s2", session(roomId, bystander)).block();

        // when
        registry.closeUserEverywhere(userId).block();

        // then: 옆 사람의 채널은 살아 있다 — 완료됐다면 방 전체를 끊은 것이다
        StepVerifier.create(registry.outbound("s2"))
                .expectTimeout(java.time.Duration.ofMillis(200))
                .verify();
    }

    @Test
    @DisplayName("등록 — Redis 명부 등재가 실패해도 접속은 성립한다(전달은 로컬 자료구조로 돈다)")
    void register_survives_redis_failure() {
        // given
        given(sessionRepository.add(any(), any(), any())).willReturn(Mono.error(new RuntimeException("redis down")));

        // when & then
        StepVerifier.create(registry.register("s1", session(roomId, userId))).expectComplete()
                .verify(java.time.Duration.ofSeconds(5));

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
        // given
        registry.register("s1", session(roomId, userId)).block();
        given(sessionRepository.remove(any(), any())).willReturn(Mono.error(new RuntimeException("redis down")));

        // when
        StepVerifier.create(registry.unregister(roomId, "s1")).expectComplete()
                .verify(java.time.Duration.ofSeconds(5));

        // then
        // 로컬 정리는 Redis와 무관하게 끝나 있어야 한다
        StepVerifier.create(registry.outbound("s1")).expectComplete()
                .verify(java.time.Duration.ofSeconds(5));
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
                .expectComplete()
                .verify(java.time.Duration.ofSeconds(5));
        assertThat(registry.closeStatusOf("s1")).isEqualTo(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    @DisplayName("방 종료 — 정상 종료(1000)로 닫는다. 사유는 앞서 보낸 SYSTEM이 전달")
    void room_end_closes_normally() {
        // given
        registry.register("s1", session(roomId, userId)).block();
        // 종료 전에는 사유가 미정이다 — closeStatusOf의 기본값(NORMAL)만 보면 closeAll을 지워도 통과한다
        assertThat(registry.isTerminating("s1")).isFalse();

        registry.closeAll(roomId).block();

        assertThat(registry.isTerminating("s1")).isTrue();

        // when & then
        StepVerifier.create(registry.terminationSignal("s1"))
                .expectNext(CloseStatus.NORMAL)
                .expectComplete()
                .verify(java.time.Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("제어 신호 — 모르는 세션은 영영 발화하지 않는다(빈 Mono면 멀쩡한 세션이 즉시 닫힌다)")
    void termination_signal_never_fires_for_unknown_session() {
        // when & then
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
        // when & then
        assertThat(registry.consumerCannotKeepUp(result)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = Sinks.EmitResult.class,
            names = { "OK", "FAIL_TERMINATED", "FAIL_CANCELLED", "FAIL_NON_SERIALIZED" })
    @DisplayName("종료 판정 — 일시 경합은 세션이 멀쩡하므로 종료 대상이 아니다")
    void emit_results_that_must_not_close_session(Sinks.EmitResult result) {
        // given
        // FAIL_NON_SERIALIZED로 끊으면 부하가 오를수록 멀쩡한 시청자를 끊고, 재접속이 부하를 더 올린다

        // when & then
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
    @DisplayName("동시 emit — 여러 스레드가 같은 세션에 보내도 조용히 사라지지 않는다(드롭은 계수된다)")
    void concurrent_emit_is_never_silently_lost() throws Exception {
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

    /**
     * 구독자를 {@code onNext} 안에 머물게 해 경합을 <b>확률이 아니라 약속</b>으로 만든다.
     *
     * <p>안전 스펙으로 만든 Sink는 직렬화 래퍼를 끼우는데, 그 락이 {@code tryEmitNext} 전 구간
     * — 적재부터 구독자 {@code onNext}까지 — 에 걸쳐 잡힌다. 그래서 구독자가 머무는 동안 다른 스레드의
     * emit은 <b>반드시</b> {@code FAIL_NON_SERIALIZED}가 된다. (실측, reactor-core 3.8.5: 구독자가
     * 200ms 머무는 동안 다른 스레드 emit이 FAIL_NON_SERIALIZED를 받고 그 값은 수신되지 않았다.)
     *
     * <p>이 장치가 없으면 경합이 µs 단위로만 스쳐서, 규모를 키워도 "경합이 없었다"와 "경합이 있었고
     * 재시도가 흡수했다"를 구별할 수 없다 — 그 둘이 구별되지 않으면 재시도 자체를 지우는 변경도 통과한다.
     */
    private static final class Lingering implements java.util.function.Consumer<OutboundMessage> {

        private final List<OutboundMessage> received = new CopyOnWriteArrayList<>();
        private final java.util.concurrent.atomic.AtomicBoolean inside =
                new java.util.concurrent.atomic.AtomicBoolean();
        private final Duration linger;

        private Lingering(Duration linger) {
            this.linger = linger;
        }

        @Override
        public void accept(OutboundMessage message) {
            received.add(message);
            inside.set(true);
            java.util.concurrent.locks.LockSupport.parkNanos(linger.toNanos());
            inside.set(false);
        }

        /**
         * 구독자가 지금 {@code onNext} 안에 있을 때까지 기다린다.
         *
         * <p><b>park이 아니라 spin이다.</b> 깨어나는 지연이 머무는 시간과 같은 자릿수면 창을 놓쳐
         * 경합이 안 생기고, 그러면 재시도를 지워도 테스트가 통과한다 — 이 장치가 막으려던 바로 그 구멍이
         * 타이밍 때문에 다시 열린다. spin은 ns 안에 반응한다.
         */
        void awaitInsideOnNext() {
            while (!inside.get()) {
                Thread.onSpinWait();
            }
        }
    }

    private Lingering subscribeLingering(String sessionId, Duration linger) {
        Lingering consumer = new Lingering(linger);
        registry.outbound(sessionId).subscribe(consumer);
        return consumer;
    }

    @Test
    @DisplayName("경합이 예산 안이면 재시도가 넘겨 준다 — 머무는 구독자 뒤에서 보내도 도착한다")
    void contention_within_budget_is_carried_over_by_retry() throws Exception {
        // given: 구독자가 1ms 머무는 세션. 예산(5ms)보다 짧으므로 재시도가 그 구간을 넘길 수 있다.
        registry.register("s1", session(roomId, userId)).block();
        Lingering consumer = subscribeLingering("s1", Duration.ofMillis(1));

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread contender = new Thread(() -> {
            try {
                consumer.awaitInsideOnNext();          // 락이 잡힌 순간에 들어간다
                registry.sendToSession("s1", out("ACK")).block();
            } catch (Throwable t) {
                failure.set(t);
            }
        });

        // when: 첫 emit이 구독자 안에 머무는 동안 다른 스레드가 같은 Sink로 보낸다
        contender.start();
        registry.sendToSession("s1", out("NORMAL")).block();
        contender.join(TimeUnit.SECONDS.toMillis(30));

        // then: 재시도가 경합을 넘겨 줬으므로 둘 다 도착하고 버린 것이 없다.
        // 재시도를 지우면(예산 0) 두 번째가 첫 실패에서 바로 버려져 이 단언이 깨진다.
        assertThat(failure.get()).isNull();
        assertThat(registry.droppedOnContention()).as("예산 안의 경합인데 버렸다").isZero();
        assertThat(consumer.received).extracting(OutboundMessage::type)
                .containsExactlyInAnyOrder("NORMAL", "ACK");
    }

    @Test
    @DisplayName("경합이 예산을 넘기면 그 메시지만 버리고 — 버린 것은 센다")
    void contention_beyond_budget_drops_that_message_and_counts_it() throws Exception {
        // given: 구독자가 20ms 머무는 세션. 예산(5ms)을 넘기므로 재시도가 소진된다.
        registry.register("s1", session(roomId, userId)).block();
        Lingering consumer = subscribeLingering("s1", Duration.ofMillis(20));

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread contender = new Thread(() -> {
            try {
                consumer.awaitInsideOnNext();
                registry.sendToSession("s1", out("ACK")).block();
            } catch (Throwable t) {
                failure.set(t);
            }
        });

        // when
        contender.start();
        registry.sendToSession("s1", out("NORMAL")).block();
        contender.join(TimeUnit.SECONDS.toMillis(30));

        // then: 버린 것이 정확히 하나로 세어진다. 이 코드가 약속한 것은 "안 버린다"가 아니라
        // "버렸으면 센다"인데, 드롭 분기가 발화하는 테스트가 없으면 그 계수를 지우는 변경도 통과한다.
        assertThat(failure.get()).isNull();
        assertThat(registry.droppedOnContention()).as("예산을 넘긴 경합을 세지 않았다").isEqualTo(1);
        assertThat(consumer.received).extracting(OutboundMessage::type).containsExactly("NORMAL");

        // 경합은 "그 순간 다른 스레드가 쥐고 있었다"는 뜻일 뿐이라 세션은 멀쩡해야 한다 —
        // 버퍼 초과(1013)와 같은 처분으로 묶으면 부하가 오를수록 멀쩡한 시청자를 끊는다.
        assertThat(registry.isTerminating("s1")).as("일시 경합으로 세션을 끊었다").isFalse();
    }

    @Test
    @DisplayName("방 팬아웃은 예산 하나를 나눠 쓴다 — 세션마다 새로 주면 상한이 세션 수만큼 곱해진다")
    void room_fanout_shares_one_budget_across_sessions() {
        // given: 한 방에 세션 여럿
        int sessions = 40;
        for (int i = 0; i < sessions; i++) {
            registry.register("s" + i, session(roomId, UUID.randomUUID())).block();
            registry.outbound("s" + i).subscribe(m -> { });
        }
        long before = registry.budgetsCreated();

        // when: 한 번 뿌린다
        registry.sendToRoomLocal(roomId, out("NORMAL")).block();

        // then: 예산은 하나다. 호출마다 새로 주면 재시도 상한이 세션 수에 비례해 곱해지는데
        // (40세션이면 5ms가 최악 200ms로), 그 자리가 방 전체 중계를 담당하는 Redis 구독 스레드다.
        // 되돌려도 드롭은 줄거나 같으므로 결과를 보는 단언으로는 이 회귀가 잡히지 않는다.
        assertThat(registry.budgetsCreated() - before)
                .as("fan-out 1회가 만든 예산 수")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("방 팬아웃과 ack가 겹쳐도 조용히 사라지거나 겹쳐 오지 않는다")
    void room_fanout_with_concurrent_acks_accounts_for_every_message() throws Exception {
        // given: 한 방에 세션 여럿. 운영에서도 브로드캐스트(Redis 구독 스레드)가 방 전체에 뿌리는 동안
        // 각 세션의 ack(WS 이벤트루프)가 같은 Sink로 들어온다. 위 두 테스트가 경합 처분을 결정적으로
        // 고정하고, 이 테스트는 그 처분이 <b>방 규모의 라우팅</b>에서도 어긋나지 않는지를 본다.
        int sessions = 60;
        int broadcasts = 40;
        int acksPerSession = 40;
        List<List<OutboundMessage>> inboxes = new ArrayList<>();
        List<String> sessionIds = new ArrayList<>();
        for (int i = 0; i < sessions; i++) {
            String sid = "s" + i;
            sessionIds.add(sid);
            registry.register(sid, session(roomId, UUID.randomUUID())).block();
            List<OutboundMessage> inbox = new CopyOnWriteArrayList<>();
            inboxes.add(inbox);
            registry.outbound(sid).subscribe(inbox::add);
        }

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        // 스레드 예외를 모은다 — 안 모으면 전송 건수가 줄어 "받은 수 + 센 드롭" 불일치로 실패하는데
        // 진짜 원인은 stderr에만 남는다. 두 스레드의 역할이 달라 어느 쪽이 죽었는지가 진단을 가른다.
        List<Throwable> failures = new CopyOnWriteArrayList<>();

        // when: 브로드캐스트 스레드와 ack 스레드가 같은 Sink들을 동시에 때린다
        new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < broadcasts; i++) {
                    registry.sendToRoomLocal(roomId, out("NORMAL")).block();
                }
            } catch (Throwable t) {
                failures.add(t);
            } finally {
                done.countDown();
            }
        }).start();
        new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < acksPerSession; i++) {
                    for (String sid : sessionIds) {
                        registry.sendToSession(sid, out("ACK")).block();
                    }
                }
            } catch (Throwable t) {
                failures.add(t);
            } finally {
                done.countDown();
            }
        }).start();
        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        assertThat(failures).as("전송이 예외로 죽었다면 아래 수치의 원인이 경합이 아니다").isEmpty();

        // then: 여기서 드롭 0을 단언하지 않는 것은 규모가 커지면 예산이 빠듯해져서다 — 실측으로
        // 12,000세션 팬아웃 1회가 3.96ms(예산 5ms 안)였지만 JIT 콜드 상태에서 10.72ms로 늘며 49만 건
        // 중 2건이 드롭됐고, 5만 세션에서는 205만 건 중 10건이었다. 경합 처분 자체는 위 두 테스트가
        // 결정적으로 고정하므로, 여기서는 그것을 다시 재지 않고 라우팅만 본다.
        //
        // ⚠️ 이 관용이 발행 실패에 대한 "실시간 best-effort, 영속만 보장" 계약과 같은 것은 아니다.
        // 그 계약은 Redis 발행이 실패한 경우를 가리키고, 여기 드롭은 세션 Sink 경합이라 실패 모드가
        // 다르다. 같은 계열의 판단이라는 뜻일 뿐, 그 문구가 이 자리를 덮어 주지는 않는다.
        //
        // 지키는 것은 둘이다. <b>조용히</b> 사라지지 않는 것 — 받은 수와 센 드롭의 합이 보낸 수와
        // 맞아야 한다. 그리고 <b>종류별로도 넘치지 않는 것</b> — 총량만 보면 ACK 하나가 남의 세션으로
        // 가고 NORMAL 하나가 드롭으로 세어진 경우가 가려진다.
        long totalReceived = inboxes.stream().mapToLong(List::size).sum();
        assertThat(totalReceived + registry.droppedOnContention())
                .as("받은 수 + 센 드롭")
                .isEqualTo((long) sessions * (broadcasts + acksPerSession));
        assertThat(inboxes).allSatisfy(inbox -> {
            assertThat(inbox.size()).isLessThanOrEqualTo(broadcasts + acksPerSession);
            assertThat(inbox.stream().filter(m -> "ACK".equals(m.type())).count())
                    .as("남의 세션 ack가 섞였다").isLessThanOrEqualTo(acksPerSession);
            assertThat(inbox.stream().filter(m -> "NORMAL".equals(m.type())).count())
                    .as("브로드캐스트가 겹쳐 왔다").isLessThanOrEqualTo(broadcasts);
        });
    }

    @Test
    @DisplayName("구독 전에 보낸 프레임도 도착한다 — 등재와 구독 사이의 창을 버퍼가 받아 준다")
    void emit_before_subscribe_is_not_lost() {
        // given: 핸들러는 세션을 등재한 뒤에 아웃바운드를 구독한다. 그 사이에 들어온 프레임이
        // 버려지면 입장 직후의 ROOM_INFO·SYSTEM이 조용히 사라진다.
        registry.register("s1", session(roomId, userId)).block();

        // when: 아직 구독 전인데 보낸다
        registry.sendToSession("s1", out("ROOM_INFO")).block();

        // then: 구독이 붙는 순간 받는다
        List<OutboundMessage> received = new CopyOnWriteArrayList<>();
        registry.outbound("s1").subscribe(received::add);
        assertThat(received).extracting(OutboundMessage::type).containsExactly("ROOM_INFO");
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
                .expectComplete()
                .verify(java.time.Duration.ofSeconds(5));

        // then: 사유는 1013 — 정상 종료(1000)면 프론트가 곧장 다시 붙어 같은 상황을 반복한다
        assertThat(registry.closeStatusOf("s1")).isEqualTo(CloseStatus.SERVICE_OVERLOAD);
    }

    @Test
    @DisplayName("버퍼 초과와 강퇴는 서로 다른 코드로 닫힌다 — 프론트가 재접속 여부를 구분할 수 있어야 한다")
    void overflow_and_kick_use_distinct_close_codes() {
        // given: 두 세션을 같은 방에 둔다. 하나는 버퍼를 넘기고, 하나는 강퇴한다.
        UUID otherUserId = UUID.randomUUID();
        registry.register("overflowing", session(roomId, userId)).block();
        registry.register("kicked", session(roomId, otherUserId)).block();

        // when
        StepVerifier.create(registry.outbound("overflowing"), 0)
                .then(() -> {
                    for (int i = 0; i < ChatSessionRegistry.OUTBOUND_BUFFER_SIZE * 2; i++) {
                        registry.sendToSession("overflowing", out("NORMAL")).block();
                    }
                })
                .thenCancel()
                .verify();
        registry.closeUser(roomId, otherUserId).block();

        // then
        assertThat(registry.closeStatusOf("overflowing")).isEqualTo(CloseStatus.SERVICE_OVERLOAD);
        assertThat(registry.closeStatusOf("kicked")).isEqualTo(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    @DisplayName("레이트리밋 창 — 걸린 뒤에는 남은 시간이 나오고, 지나면 다시 0이 된다")
    void rate_limit_window_reports_remaining_then_clears() {
        // given
        registry.register("s1", session(roomId, userId)).block();
        assertThat(registry.rateLimitRetryAfterSeconds("s1")).isZero();

        // when: 3초 제한이 걸렸다고 기록
        registry.recordRateLimited("s1", 3);

        // then: 남은 시간이 나온다(0이면 "제한 없음"과 구분되지 않는다)
        assertThat(registry.rateLimitRetryAfterSeconds("s1")).isBetween(1L, 3L);

        // 창이 지나면 다시 물어봐야 한다
        registry.recordRateLimited("s1", 0);
        assertThat(registry.rateLimitRetryAfterSeconds("s1")).isZero();
    }

    @Test
    @DisplayName("레이트리밋 창 — 모르는 세션은 제한 없음으로 답한다(막는 데만 쓰는 값)")
    void rate_limit_window_unknown_session_is_not_limited() {
        // when & then
        assertThat(registry.rateLimitRetryAfterSeconds("없는세션")).isZero();
    }

    @Test
    @DisplayName("거부 응답 솎기 — 첫 건은 답하고, 간격 안의 반복은 조용히 버린다(연결은 유지)")
    void rejection_reply_is_throttled_but_first_always_answers() {
        // given
        registry.register("s1", session(roomId, userId)).block();

        // when & then: 첫 거부는 반드시 답한다 — 클라가 무엇이 틀렸는지 알아야 고친다
        assertThat(registry.shouldReplyToRejection("s1")).isTrue();
        // 곧바로 이어지는 거부는 답하지 않는다 — 되돌림 비용이 곧 공격 표면이다
        assertThat(registry.shouldReplyToRejection("s1")).isFalse();
        assertThat(registry.shouldReplyToRejection("s1")).isFalse();
        // 솎아낼 뿐 끊지는 않는다 — 200자 초과를 반복하는 정상 사용자를 잃으면 안 된다
        assertThat(registry.isTerminating("s1")).isFalse();
    }

    @Test
    @DisplayName("거부 응답 솎기 — 모르는 세션엔 답하지 않는다")
    void rejection_reply_unknown_session_is_not_answered() {
        // when & then
        assertThat(registry.shouldReplyToRejection("없는세션")).isFalse();
    }

    @Test
    @DisplayName("전송 경로 강퇴 확인 — 그 세션을 1008로 끊는다(종료 신호를 놓친 Pod의 자가 복구)")
    void terminateKicked_closes_session() {
        // given
        registry.register("s1", session(roomId, userId)).block();

        // when
        registry.terminateKicked("s1");

        // then
        assertThat(registry.isTerminating("s1")).isTrue();
        assertThat(registry.closeStatusOf("s1")).isEqualTo(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    @DisplayName("레이트리밋 창 — 남은 시간을 올림한다(절삭하면 제한 없음과 구분이 안 된다)")
    void rate_limit_window_rounds_up() {
        // given
        registry.register("s1", session(roomId, userId)).block();

        // when
        registry.recordRateLimited("s1", 3);

        // then: 즉시 읽어도 3이 나와야 한다 — 절삭이면 2로 떨어진다
        assertThat(registry.rateLimitRetryAfterSeconds("s1")).isEqualTo(3L);
    }

    @Test
    @DisplayName("레이트리밋 창 — 등록 직후에는 제한이 없다(nanoTime 원점이 음수여도)")
    void rate_limit_window_is_clear_right_after_register() {
        // given & when
        registry.register("s1", session(roomId, userId)).block();

        // then: 0을 센티널로 쓰면 nanoTime이 음수인 JVM에서 전 세션이 막힌다
        assertThat(registry.rateLimitRetryAfterSeconds("s1")).isZero();
    }

    @Test
    @DisplayName("시청자 수 — 창 안의 재조회는 Redis로 가지 않는다(입장이 방 크기에 비례해 비싸지면 안 된다)")
    void activeCount_servedFromCacheWithinWindow() {
        // given: 실제 흐름대로 등록 뒤에 묻는다 — 캐시는 이 Pod에 남아 있는 방에만 붙는다
        // (조회를 다녀온 사이 방이 비면 담지 않는다. 담으면 회수 주체가 사라져 항목이 영영 남는다.)
        registry.register("s1", session(roomId, UUID.randomUUID())).block();
        given(sessionRepository.count(roomId)).willReturn(Mono.just(42L));

        // when: 같은 방에 연달아 두 번 묻는다
        StepVerifier.create(registry.getActiveCount(roomId)).expectNext(42L).expectComplete()
                .verify(java.time.Duration.ofSeconds(5));
        StepVerifier.create(registry.getActiveCount(roomId)).expectNext(42L).expectComplete()
                .verify(java.time.Duration.ofSeconds(5));

        // then
        then(sessionRepository).should(times(1)).count(roomId);
    }

    @Test
    @DisplayName("시청자 수 — 조회 실패는 캐시에 굳히지 않는다(다음 입장이 값 없이 나가면 안 된다)")
    void activeCount_doesNotCacheFailures() {
        // given
        registry.register("s1", session(roomId, UUID.randomUUID())).block();
        given(sessionRepository.count(roomId))
                .willReturn(Mono.error(new RuntimeException("redis down")))
                .willReturn(Mono.just(7L));

        // when & then: 첫 번째는 실패하고, 두 번째는 다시 물어서 값을 얻는다
        StepVerifier.create(registry.getActiveCount(roomId)).verifyError(RuntimeException.class);
        StepVerifier.create(registry.getActiveCount(roomId)).expectNext(7L).expectComplete()
                .verify(java.time.Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("방 종료 재확인 창 — 등록 직후에는 묻지 않는다(입장 게이트가 방금 확인했다)")
    void roomAliveRecheck_notDueRightAfterRegister() {
        // given
        UUID room = UUID.randomUUID();
        registry.register("s1", session(room, UUID.randomUUID())).block();

        // when & then: 첫 프레임마다 Redis를 치면 창을 둔 의미가 없다
        assertThat(registry.shouldRecheckRoomAlive("s1")).isFalse();
    }

    @Test
    @DisplayName("방 종료 재확인 창 — 모르는 세션은 묻지 않는다")
    void roomAliveRecheck_unknownSession() {
        // when & then
        assertThat(registry.shouldRecheckRoomAlive("없는세션")).isFalse();
    }

    @Test
    @DisplayName("종료 래치 — 한 번 표시하면 계속 종료로 답한다(창이 다시 열려도 되돌아가지 않는다)")
    void roomEndedLatch_isSticky() {
        // given
        UUID room = UUID.randomUUID();
        registry.register("s1", session(room, UUID.randomUUID())).block();
        assertThat(registry.isRoomKnownEnded("s1")).isFalse();

        // when
        registry.markRoomEnded("s1");

        // then: 창으로만 두면 확인 직후부터 다음 확인까지의 프레임이 그대로 통과해 이력에 쌓인다
        assertThat(registry.isRoomKnownEnded("s1")).isTrue();
        assertThat(registry.isRoomKnownEnded("s1")).isTrue();
    }

    @Test
    @DisplayName("종료 래치 — 모르는 세션은 종료로 답하지 않는다(막는 데만 쓰는 값)")
    void roomEndedLatch_unknownSession() {
        // when & then
        registry.markRoomEnded("없는세션");   // 조용히 무시
        assertThat(registry.isRoomKnownEnded("없는세션")).isFalse();
    }

    @Test
    @DisplayName("시청자 수 캐시 — 방이 비면 같이 걷힌다(캐시 자체가 누수원이 되면 안 된다)")
    void activeCount_cacheEvictedWhenRoomEmpties() {
        // given: 한 명이 들어와 값을 캐시한 뒤 나간다
        UUID room = UUID.randomUUID();
        given(sessionRepository.count(room)).willReturn(Mono.just(1L), Mono.just(9L));
        registry.register("s1", session(room, UUID.randomUUID())).block();
        StepVerifier.create(registry.getActiveCount(room)).expectNext(1L).expectComplete()
                .verify(java.time.Duration.ofSeconds(5));
        registry.unregister(room, "s1").block();

        // when: 같은 방에 다시 들어온다
        registry.register("s2", session(room, UUID.randomUUID())).block();

        // then: 캐시가 남아 있으면 이전 값이 나오고, 방송이 끝난 방의 항목이 계속 쌓인다
        StepVerifier.create(registry.getActiveCount(room)).expectNext(9L).expectComplete()
                .verify(java.time.Duration.ofSeconds(5));
        then(sessionRepository).should(times(2)).count(room);
    }

    @Test
    @DisplayName("모르는 세션에 어떤 호출이 와도 조용히 무시한다 — 정리·경합 경로는 이미 사라진 세션을 부른다")
    void unknownSession_isHarmlessForEveryEntryPoint() {
        // given: 퇴장과 방 fan-out·강퇴 이벤트는 서로 다른 스레드에서 오므로, 이미 빠진 세션을
        // 가리키는 호출이 정상적으로 발생한다. 여기서 NPE가 나면 그 스트림이 통째로 죽는다.
        String gone = "이미-없는-세션";

        // when & then
        assertThatCode(() -> {
            registry.recordRateLimited(gone, 3);
            registry.markRoomEnded(gone);
            registry.terminateRoomEnded(gone);
            registry.terminateKicked(gone);
            registry.expireRoomAliveWindow(gone);
            registry.sendToSession(gone, out("SYSTEM")).block();
        }).doesNotThrowAnyException();

        assertThat(registry.rateLimitRetryAfterSeconds(gone)).isZero();
        assertThat(registry.shouldReplyToRejection(gone)).isFalse();
        assertThat(registry.shouldRecheckRoomAlive(gone)).isFalse();
        assertThat(registry.isRoomKnownEnded(gone)).isFalse();
        assertThat(registry.isTerminating(gone)).isFalse();
        StepVerifier.create(registry.outbound(gone)).expectComplete()
                .verify(java.time.Duration.ofSeconds(5));   // 빈 스트림이어야 한다
    }

    @Test
    @DisplayName("종료 사유가 정해지지 않았으면 정상 종료(1000)로 닫는다 — 클라가 먼저 끊은 경우")
    void closeStatus_defaultsToNormal() {
        // given
        UUID room = UUID.randomUUID();
        registry.register("s1", session(room, UUID.randomUUID())).block();

        // when & then: 서버가 정한 사유가 없으면 정책 위반 코드를 붙이면 안 된다 — 프론트가 재접속을 포기한다
        assertThat(registry.closeStatusOf("s1")).isEqualTo(CloseStatus.NORMAL);
        assertThat(registry.closeStatusOf("모르는세션")).isEqualTo(CloseStatus.NORMAL);
    }

    @Test
    @DisplayName("강퇴로 끊긴 세션은 1008로 닫힌다 — 프론트는 이 코드로 재접속 금지를 판단한다")
    void closeStatus_reflectsKick() {
        // given
        UUID room = UUID.randomUUID();
        registry.register("s1", session(room, UUID.randomUUID())).block();

        // when
        registry.terminateKicked("s1");

        // then
        assertThat(registry.closeStatusOf("s1")).isEqualTo(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    @DisplayName("방 종료 재확인 창 — 간격이 지나면 다시 열리고, 통과한 뒤에는 곧바로 닫힌다")
    void roomAliveRecheck_reopensAfterInterval() {
        // given: 30초를 실제로 기다리지 않고 마지막 확인 시각만 과거로 민다
        UUID room = UUID.randomUUID();
        registry.register("s1", session(room, UUID.randomUUID())).block();
        registry.expireRoomAliveWindow("s1");

        // when & then: 부등호나 CAS를 잘못 고쳐 창이 영영 안 열려도 이 단언이 없으면 전부 초록이다
        assertThat(registry.shouldRecheckRoomAlive("s1")).isTrue();
        // 한 번 통과하면 그 시각으로 갱신되므로 바로 다음 호출은 막힌다(중복 조회 방지)
        assertThat(registry.shouldRecheckRoomAlive("s1")).isFalse();
    }

    @Test
    @DisplayName("방 종료로 끊긴 세션은 1000으로 닫힌다 — 강퇴(1008)와 달라야 프론트가 재접속 금지로 오독하지 않는다")
    void closeStatus_reflectsRoomEnded() {
        // given
        UUID room = UUID.randomUUID();
        registry.register("s1", session(room, UUID.randomUUID())).block();

        // 종료 전에는 사유가 미정이라 closeStatusOf가 기본값 NORMAL을 돌려준다 —
        // 그것만 보면 terminateRoomEnded를 통째로 지워도 통과한다. 그래서 종료 여부를 함께 고정한다.
        assertThat(registry.isTerminating("s1")).isFalse();

        // when
        registry.terminateRoomEnded("s1");

        // then: 실제로 종료됐고, 정상 종료 경로(closeAll)와 같은 코드여야 와이어에서 구분되지 않는다
        assertThat(registry.isTerminating("s1")).isTrue();
        assertThat(registry.closeStatusOf("s1")).isEqualTo(CloseStatus.NORMAL);
        StepVerifier.create(registry.terminationSignal("s1")).expectNext(CloseStatus.NORMAL).expectComplete()
                .verify(java.time.Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("시청자 수 캐시 — 조회 중에 방이 비면 담지 않는다(회수 주체가 사라진 뒤 생긴 항목은 영영 남는다)")
    void activeCount_doesNotCacheWhenRoomEmptiedDuringLookup() {
        // given: Redis를 다녀오는 사이에 마지막 세션이 끊기는 상황.
        // 담는 시점과 회수 시점이 다른 락 구간이라, 그냥 넣으면 unregister가 먼저 지나간 뒤 항목이 새로 생긴다.
        UUID room = UUID.randomUUID();
        registry.register("s1", session(room, UUID.randomUUID())).block();
        given(sessionRepository.count(room))
                .willReturn(Mono.just(5L).delayElement(Duration.ofMillis(60)))
                .willReturn(Mono.just(9L));

        // when: 조회를 걸어두고 그 사이에 방을 비운다
        Mono<Long> inFlight = registry.getActiveCount(room);
        StepVerifier.create(inFlight.doOnSubscribe(sub -> {
                    registry.unregister(room, "s1").block();   // 조회 응답 전에 방이 빈다
                }))
                .expectNext(5L)
                .expectComplete()
                .verify(java.time.Duration.ofSeconds(5));

        // then: 그 방에 다시 들어오면 캐시가 아니라 Redis를 봐야 한다.
        // 고아 항목이 남았다면 아래가 9가 아니라 5로 나온다.
        registry.register("s2", session(room, UUID.randomUUID())).block();
        StepVerifier.create(registry.getActiveCount(room)).expectNext(9L).expectComplete()
                .verify(java.time.Duration.ofSeconds(5));
    }
}
