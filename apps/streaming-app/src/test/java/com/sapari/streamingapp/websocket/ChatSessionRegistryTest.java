package com.sapari.streamingapp.websocket;

import com.sapari.chat.application.port.ChatMetrics;
import com.sapari.chat.domain.rule.ChatPermissionPolicy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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


    /** 반응형 종결자(.block() / StepVerifier) 상한 — 없으면 회귀가 테스트를 실패시키는 대신 <b>멈추게</b> 한다(근거: modules/chat/AGENTS.md). */
    private static final Duration VERIFY_CEILING = Duration.ofSeconds(10);
    private ChatSessionRepository sessionRepository;
    private ChatSessionRegistry registry;

    private final UUID roomId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        sessionRepository = mock(ChatSessionRepository.class);
        given(sessionRepository.add(any(), any(), any())).willReturn(Mono.empty());
        given(sessionRepository.remove(any(), any())).willReturn(Mono.empty());
        registry = new ChatSessionRegistry(sessionRepository, new ChatPermissionPolicy(), ChatMetrics.NOOP,
                TEST_EMIT_SPIN.toNanos(), TEST_COMPLETE_SPIN.toNanos());
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
        registry.register("s1", session(roomId, userId)).block(VERIFY_CEILING);
        then(sessionRepository).should(times(1)).add(roomId, "s1", userId);

        OutboundMessage msg = out("NORMAL");

        // when & then
        StepVerifier.create(registry.outbound("s1"))
                .then(() -> registry.sendToSession("s1", msg).block(VERIFY_CEILING))
                .expectNext(msg)
                .thenCancel()
                .verify(VERIFY_CEILING);
    }

    @Test
    @DisplayName("sendToRoomLocal — 같은 방 세션은 받는다")
    void room_local_delivers_to_same_room() {
        // given
        registry.register("s1", session(roomId, userId)).block(VERIFY_CEILING);

        OutboundMessage msg = out("SYSTEM");

        // when & then
        StepVerifier.create(registry.outbound("s1"))
                .then(() -> registry.sendToRoomLocal(roomId, msg).block(VERIFY_CEILING))
                .expectNext(msg)
                .thenCancel()
                .verify(VERIFY_CEILING);
    }

    @Test
    @DisplayName("sendToRoomLocal — 다른 방 세션은 안 받는다")
    void room_local_skips_other_room() {
        // given
        registry.register("s1", session(roomId, userId)).block(VERIFY_CEILING);

        // when & then
        StepVerifier.create(registry.outbound("s1"))
                .then(() -> registry.sendToRoomLocal(UUID.randomUUID(), out("SYSTEM")).block(VERIFY_CEILING))
                .expectNoEvent(Duration.ofMillis(50))
                .thenCancel()
                .verify(VERIFY_CEILING);
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
        registry.register("s1", session(roomId, userId)).block(VERIFY_CEILING);

        registry.unregister(roomId, "s1").block(VERIFY_CEILING);
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
        registry.register("s1", session(roomId, userId)).block(VERIFY_CEILING);

        // when & then
        StepVerifier.create(registry.outbound("s1"))
                .then(() -> registry.closeAll(roomId).block(VERIFY_CEILING))
                .expectComplete()
                .verify(java.time.Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("closeUser — 해당 유저 세션의 아웃바운드만 완료")
    void close_user_completes_target_outbound() {
        // given
        registry.register("s1", session(roomId, userId)).block(VERIFY_CEILING);

        // when
        StepVerifier.create(registry.outbound("s1"))
                .then(() -> registry.closeUser(roomId, userId).block(VERIFY_CEILING))
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
        registry.register("s1", session(roomId, userId)).block(VERIFY_CEILING);
        registry.register("s2", session(otherRoom, userId)).block(VERIFY_CEILING);

        // when & then: 방을 모르는 채로 끊어도 둘 다 닫힌다.
        // 방 색인으로 찾으면 강퇴가 일어난 방 하나만 닫히고 나머지는 살아남는다 — 그게 이 메서드가 없앤 상태다.
        //
        // 상한을 준다. 안 주면 닫히지 않은 세션에서 이 테스트가 <b>영원히 기다린다</b> — 회귀가 빨간불이
        // 아니라 멈춤으로 나타나고, 그건 회귀를 알려 주지 않는 것과 같다(되돌림 확인에서 실제로 겪었다).
        StepVerifier.create(registry.outbound("s2"))
                .then(() -> registry.closeUserEverywhere(userId).block(VERIFY_CEILING))
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
        registry.register("s1", session(roomId, userId)).block(VERIFY_CEILING);
        registry.register("s2", session(roomId, bystander)).block(VERIFY_CEILING);

        // when
        registry.closeUserEverywhere(userId).block(VERIFY_CEILING);

        // then: 옆 사람의 채널은 살아 있다 — 완료됐다면 방 전체를 끊은 것이다
        StepVerifier.create(registry.outbound("s2"))
                .expectTimeout(java.time.Duration.ofMillis(200))
                .verify(VERIFY_CEILING);
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
                .then(() -> registry.sendToRoomLocal(roomId, out("SYSTEM")).block(VERIFY_CEILING))
                .expectNextCount(1)
                .thenCancel()
                .verify(VERIFY_CEILING);
    }

    @Test
    @DisplayName("퇴장 — Redis 명부 제거가 실패해도 정리는 완료된다(호출부가 subscribe라 에러가 새면 안 됨)")
    void unregister_survives_redis_failure() {
        // given
        registry.register("s1", session(roomId, userId)).block(VERIFY_CEILING);
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
        registry.register("s1", session(roomId, userId)).block(VERIFY_CEILING);
        StepVerifier.create(registry.outbound("s1"), 0)
                .then(() -> {
                    for (int i = 0; i < 5; i++) {
                        registry.sendToSession("s1", out("NORMAL")).block(VERIFY_CEILING);
                    }
                    registry.closeUser(roomId, userId).block(VERIFY_CEILING);   // when
                })
                // 데이터 채널로는 아무것도 못 간다 — complete도 쌓인 5건 뒤에 막혀 전달되지 않는다
                .expectNoEvent(Duration.ofMillis(50))
                .thenCancel()
                .verify(VERIFY_CEILING);

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
        registry.register("s1", session(roomId, userId)).block(VERIFY_CEILING);
        // 종료 전에는 사유가 미정이다 — closeStatusOf의 기본값(NORMAL)만 보면 closeAll을 지워도 통과한다
        assertThat(registry.isTerminating("s1")).isFalse();

        registry.closeAll(roomId).block(VERIFY_CEILING);

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
                .verify(VERIFY_CEILING);
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
        registry.register("s1", session(roomId, userId)).block(VERIFY_CEILING);
        registry.register("s2", session(roomId, UUID.randomUUID())).block(VERIFY_CEILING);
        assertThat(registry.trackedRoomCount()).isEqualTo(1);

        // when: 하나만 퇴장
        registry.unregister(roomId, "s1").block(VERIFY_CEILING);

        // then: 아직 s2가 있으므로 방은 유지
        assertThat(registry.trackedRoomCount()).isEqualTo(1);

        // when: 마지막 세션도 퇴장
        registry.unregister(roomId, "s2").block(VERIFY_CEILING);

        // then: 방 항목이 남지 않는다 — 남기면 방송할 때마다 죽은 방이 쌓인다
        assertThat(registry.trackedRoomCount()).isZero();
    }

    @Test
    @DisplayName("sendToRoomGated — 같은 방 세션마다 resolver 결과를 받고, 다른 방은 받지 않는다")
    void gated_fanout_is_per_session_and_room_scoped() {
        UUID otherRoom = UUID.randomUUID();
        registry.register("s1", session(roomId, userId)).block(VERIFY_CEILING);
        registry.register("s2", session(roomId, UUID.randomUUID())).block(VERIFY_CEILING);
        registry.register("other", session(otherRoom, UUID.randomUUID())).block(VERIFY_CEILING);

        // when: resolver가 세션마다 다른 메시지를 낸다(방주인 PII 게이팅이 쓰는 방식)
        registry.sendToRoomGated(roomId, s -> out(s.userId().equals(userId) ? "OWNER" : "PLAIN")).block(VERIFY_CEILING);

        // then: 같은 방 세션은 각자 몫을 받고
        StepVerifier.create(registry.outbound("s1"))
                .expectNextMatches(m -> "OWNER".equals(m.type()))
                .thenCancel()
                .verify(VERIFY_CEILING);
        StepVerifier.create(registry.outbound("s2"))
                .expectNextMatches(m -> "PLAIN".equals(m.type()))
                .thenCancel()
                .verify(VERIFY_CEILING);

        // 다른 방 세션은 아무것도 못 받는다
        StepVerifier.create(registry.outbound("other"))
                .expectSubscription()
                .expectNoEvent(Duration.ofMillis(50))
                .thenCancel()
                .verify(VERIFY_CEILING);
    }

    @Test
    @DisplayName("동시 emit — 여러 스레드가 같은 세션에 보내도 조용히 사라지지 않는다(드롭은 계수된다)")
    void concurrent_emit_is_never_silently_lost() throws Exception {
        // given: 구독 중인 세션 하나. 실제로도 ack(WS 이벤트루프)와 브로드캐스트(Redis pubsub 스레드)가
        // 같은 세션 Sink에 동시 emit한다 — unicast Sink는 경합 시 FAIL_NON_SERIALIZED로 값을 버린다.
        registry.register("s1", session(roomId, userId)).block(VERIFY_CEILING);
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
                    if (!start.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("시작 래치가 30초 안에 열리지 않았다 — 주 스레드가 countDown 전에 터졌다. 상한이 없으면 이 스레드가 영원히 블록된 채 남아 같은 JVM 의 뒤따르는 테스트를 오염시킨다");
                    }
                    for (int i = 0; i < perThread; i++) {
                        registry.sendToSession("s1", out("NORMAL")).block(VERIFY_CEILING);
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
        /** 데이터 채널이 곱게 닫혔는지 — 종료 신호가 경합에 소진되면 영영 내려오지 않는다. */
        private final CountDownLatch completed = new CountDownLatch(1);
        private final Duration linger;

        private Lingering(Duration linger) {
            this.linger = linger;
        }

        boolean awaitDataChannelClose(Duration timeout) throws InterruptedException {
            return completed.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        /**
         * {@code park}을 <b>루프로 감싼다.</b> 단발 호출은 이유 없이 조기 반환할 수 있고(spurious wakeup은
         * 명세가 허용한다), 그러면 예산 초과를 노린 체류가 예산 밑으로 줄어 드롭이 나지 않는다 —
         * 그 테스트가 flaky하게 깨진다. 남은 시간을 다시 재서 데드라인까지 머문다.
         */
        @Override
        public void accept(OutboundMessage message) {
            received.add(message);
            inside.set(true);
            long deadline = System.nanoTime() + linger.toNanos();
            long remaining;
            while ((remaining = deadline - System.nanoTime()) > 0) {
                java.util.concurrent.locks.LockSupport.parkNanos(remaining);
            }
            inside.set(false);
        }

        /**
         * 구독자가 지금 {@code onNext} 안에 있을 때까지 기다린다.
         *
         * <p><b>park이 아니라 spin이다.</b> 깨어나는 지연이 머무는 시간과 같은 자릿수면 창을 놓쳐
         * 경합이 안 생기고, 그러면 재시도를 지워도 테스트가 통과한다 — 이 장치가 막으려던 바로 그 구멍이
         * 타이밍 때문에 다시 열린다. spin은 ns 안에 반응한다.
         *
         * <p><b>상한이 있다.</b> 상한 없이 돌면 구독자가 끝내 돌지 않는 회귀에서 이 스레드가 코어 하나를
         * 영원히 태우고, 더 나쁜 것은 그때 테스트가 <b>엉뚱한 단언에서</b> 깨진다는 것이다 — 경합이 아예
         * 없었으므로 드롭도 0이라 드롭 단언은 통과하고, 수신 목록 단언만 깨져 원인이 가려진다. 소진하면
         * 그 자리에서 사유를 들고 실패해야 한다. spin의 ns 반응성과 상한은 양립한다.
         */
        void awaitInsideOnNext() {
            long deadline = System.nanoTime() + INSIDE_ON_NEXT_TIMEOUT.toNanos();
            while (!inside.get()) {
                if (System.nanoTime() - deadline >= 0) {
                    throw new AssertionError("구독자가 " + INSIDE_ON_NEXT_TIMEOUT.toMillis()
                            + "ms 안에 onNext에 들어오지 않았다 — 경합 창이 열리지 않았으므로 "
                            + "이 테스트의 드롭 단언은 아무것도 재지 못한다");
                }
                Thread.onSpinWait();
            }
        }
    }

    /**
     * 구독자가 {@code onNext}에 들어오기를 기다리는 상한. 첫 emit이 그 자리에서 구독자를 돌리므로
     * 정상 경로는 µs다 — 이 값은 "영원히 기다리지 않는다"를 위한 것이고 타이밍 조정용이 아니다.
     */
    private static final Duration INSIDE_ON_NEXT_TIMEOUT = Duration.ofSeconds(5);

    private Lingering subscribeLingering(String sessionId, Duration linger) {
        Lingering consumer = new Lingering(linger);
        registry.outbound(sessionId).subscribe(consumer, t -> { }, consumer.completed::countDown);
        return consumer;
    }

    /**
     * 경합을 만드는 스레드. 예외를 모아 두어 "어느 쪽이 죽었는가"가 단언으로 드러나게 한다 —
     * 안 모으면 전송 건수가 줄어 회계 단언이 깨지는데 진짜 원인은 stderr에만 남는다.
     *
     * <p>daemon으로 두는 것은 <b>값을 매길 수 없는 하드닝이다.</b> 상한에 걸려 멈춘 스레드가 JVM 종료를
     * 막지 못하게 하려는 것인데, 이 빌드에서는 그 효과가 관측되지 않는다 — 10분 잠드는 non-daemon
     * 스레드를 남겨도 Gradle 테스트 워커가 정상 종료한다(리뷰어 실측). 즉 러너의 종료 방식에 기대지
     * 않겠다는 뜻이고, 비용이 0이라 둔다. 러너가 바뀌면 이 선택이 값을 갖는다.
     */
    private static Thread contenderThread(Runnable body, AtomicReference<Throwable> failure) {
        Thread thread = new Thread(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        thread.setDaemon(true);
        return thread;
    }

    /**
     * 테스트가 쓰는 예산 — 운영값(5ms / 50ms)의 <b>20배</b>.
     *
     * <p>이 값에 기대는 테스트들이 고정하는 것은 절대값이 아니라 관계다: "예산보다 짧은 경합은 넘겨진다",
     * "예산보다 긴 경합은 버려지고 계수된다", "종료 예산은 emit 예산보다 오래 간다". 예산과 체류를 같은
     * 배율로 키우면 그 관계가 글자 그대로 보존되면서 <b>마진만 20배가 된다.</b>
     *
     * <p>운영값으로 돌리면 1ms 체류 vs 5ms 예산 = 5배이고, 그 5배는 4ms 선점으로 지워진다 —
     * 실측으로 load average 13.57 구간에서 실제로 뒤집혔다. 20배 스케일에서는 같은 5배를 지우려면
     * 80ms 선점이 필요하다.
     *
     * <p>⚠️ 결정론이 아니라 <b>마진</b>이다. 벽시계는 여전히 벽시계이고 확률이 내려갈 뿐이다.
     * 시계를 가짜로 바꾸는 쪽은 안 된다 — busy-spin 루프가 가짜 시계로는 돌지 않아 재려던 기제가
     * 사라진다. 드롭 자체는 설계된 동작이므로(best-effort) 이 테스트가 요구할 수 있는 것은
     * "예산이 충분할 때 넘겨진다" 까지다.
     */
    private static final Duration TEST_EMIT_SPIN = Duration.ofMillis(100);

    /** 종료 신호 예산 — 운영값 50ms 의 20배. emit 예산과의 비(10배)가 운영과 같다. */
    private static final Duration TEST_COMPLETE_SPIN = Duration.ofSeconds(1);

    /** 예산 안에 드는 체류 — emit 예산의 1/5 (운영 비율 1ms : 5ms 와 같다). */
    private static final Duration LINGER_WITHIN_BUDGET = Duration.ofMillis(20);

    /** 예산을 넘기는 체류 — emit 예산의 2배, 종료 예산의 1/5 (운영 비율 20ms : 5ms : 50ms 와 같다). */
    private static final Duration LINGER_BEYOND_BUDGET = Duration.ofMillis(200);

    @Test
    @DisplayName("⭐ 운영 생성자는 운영 예산을 쓴다 — 넓힌 예산이 테스트 밖으로 새지 않는다")
    void production_constructor_uses_the_production_budgets() {
        // given: 이 파일의 다른 테스트들이 쓰는 생성자는 예산을 넓혀 받는다. 그 진입점이 생긴 순간
        // "운영은 무엇을 받는가"를 아무도 묻지 않는 자리가 생겼다 — 위임에서 값을 뒤바꾸거나 넓힌
        // 값을 그대로 넘겨도 다른 테스트는 전부 초록이다(자기 예산을 직접 주므로).
        // ⚠️ **Spring 이 고르는 생성자**를 불러야 한다. 상한이 설정으로 열린 뒤로 이 클래스에는
        // 좁은 생성자(기본 상한으로 위임하는 테스트 진입점)가 따로 있고, 그걸 부르면 "운영이
        // 무엇을 받는가" 를 묻는 이 칸이 운영 경로를 안 지나간다. 상한 인자가 있는 쪽이 @Autowired 다.
        ChatSessionRegistry production = new ChatSessionRegistry(
                sessionRepository, new ChatPermissionPolicy(), ChatMetrics.NOOP, 3);

        // when & then: 셋을 따로 본다. 뒤바꿔 넘기면 한 값만 보는 단언으로는 드러나지 않는다.
        assertThat(Duration.ofNanos(production.contentionSpinNanos()))
                .as("emit 경합 예산").isEqualTo(Duration.ofMillis(5));
        assertThat(Duration.ofNanos(production.completeSpinNanos()))
                .as("종료 신호 예산").isEqualTo(Duration.ofMillis(50));
        // 상한은 위임에서 자리를 잃거나 기본값으로 덮이기 쉬운 인자다 — 기본(5)과 다른 수로 본다.
        assertThat(production.maxSessionsPerUserPerRoom())
                .as("(유저, 방) 상한").isEqualTo(3);
    }

    @Test
    @DisplayName("경합이 예산 안이면 재시도가 넘겨 준다 — 머무는 구독자 뒤에서 보내도 도착한다")
    void contention_within_budget_is_carried_over_by_retry() throws Exception {
        // given: 구독자가 예산의 1/5 만큼 머무는 세션. 재시도가 그 구간을 넘길 수 있다.
        registry.register("s1", session(roomId, userId)).block(VERIFY_CEILING);
        Lingering consumer = subscribeLingering("s1", LINGER_WITHIN_BUDGET);

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread contender = contenderThread(() -> {
            consumer.awaitInsideOnNext();          // 락이 잡힌 순간에 들어간다
            registry.sendToSession("s1", out("ACK")).block(VERIFY_CEILING);
        }, failure);

        // when: 첫 emit이 구독자 안에 머무는 동안 다른 스레드가 같은 Sink로 보낸다
        contender.start();
        registry.sendToSession("s1", out("NORMAL")).block(VERIFY_CEILING);
        contender.join(TimeUnit.SECONDS.toMillis(30));

        // then: 재시도가 경합을 넘겨 줬으므로 둘 다 도착하고 버린 것이 없다.
        // 재시도를 지우면(예산 0) 두 번째가 첫 실패에서 바로 버려져 이 단언이 깨진다.
        //
        // 경합 창이 열렸는지를 먼저 본다 — 창이 안 열리면 드롭이 0이라 아래 단언이 통과하면서
        // 아무것도 재지 못한다. 그 경우 awaitInsideOnNext가 사유를 들고 실패해 여기로 온다.
        assertThat(failure.get()).as("경합 스레드가 창에 들어가지 못했다").isNull();
        assertThat(registry.droppedOnContention()).as("예산 안의 경합인데 버렸다").isZero();
        assertThat(consumer.received).extracting(OutboundMessage::type)
                .containsExactlyInAnyOrder("NORMAL", "ACK");
    }

    @Test
    @DisplayName("경합이 예산을 넘기면 그 메시지만 버리고 — 버린 것은 센다")
    void contention_beyond_budget_drops_that_message_and_counts_it() throws Exception {
        // given: 구독자가 20ms 머무는 세션. 예산(5ms)을 넘기므로 재시도가 소진된다.
        registry.register("s1", session(roomId, userId)).block(VERIFY_CEILING);
        Lingering consumer = subscribeLingering("s1", LINGER_BEYOND_BUDGET);

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread contender = contenderThread(() -> {
            consumer.awaitInsideOnNext();
            registry.sendToSession("s1", out("ACK")).block(VERIFY_CEILING);
        }, failure);

        // when
        contender.start();
        registry.sendToSession("s1", out("NORMAL")).block(VERIFY_CEILING);
        contender.join(TimeUnit.SECONDS.toMillis(30));

        // then: 버린 것이 정확히 하나로 세어진다. 이 코드가 약속한 것은 "안 버린다"가 아니라
        // "버렸으면 센다"인데, 드롭 분기가 발화하는 테스트가 없으면 그 계수를 지우는 변경도 통과한다.
        assertThat(failure.get()).as("경합 스레드가 창에 들어가지 못했다").isNull();
        assertThat(registry.droppedOnContention()).as("예산을 넘긴 경합을 세지 않았다").isEqualTo(1);
        assertThat(consumer.received).extracting(OutboundMessage::type).containsExactly("NORMAL");

        // 경합은 "그 순간 다른 스레드가 쥐고 있었다"는 뜻일 뿐이라 세션은 멀쩡해야 한다 —
        // 버퍼 초과(1013)와 같은 처분으로 묶으면 부하가 오를수록 멀쩡한 시청자를 끊는다.
        assertThat(registry.isTerminating("s1")).as("일시 경합으로 세션을 끊었다").isFalse();
    }

    /**
     * 종료가 <b>emit 예산을 넘는 경합</b>을 만나도 데이터 채널이 곱게 닫히는지 본다.
     *
     * <p>구독자를 20ms 머물게 하면 emit 예산(5ms)은 넘기지만 종료 예산(50ms) 안이다 — 두 예산을 따로
     * 둔 이유가 정확히 그 구간이다. 종료 신호가 걸려야 버퍼 잔여분이 흘러나가고 강퇴 직전에 보낸
     * SYSTEM(KICKED)이 클라에 닿는다.
     *
     * <p><b>호출지마다 따로 걸어야 한다.</b> 예산은 호출지가 고르고(종료는 {@code completeDeadline()},
     * 전송은 {@code emitDeadline()}), 둘 다 정당한 관용구로 같은 파일에 있다. 그래서 종료 자리에
     * {@code emitDeadline()}을 적는 실수는 복사 한 번이면 생기는데, 어느 한 호출지만 덮으면 나머지는
     * 아무 테스트도 깨뜨리지 않고 바뀐다 — 그리고 fan-out 쪽은 예산 하나를 세션들이 나눠 쓰므로
     * 단건 경로보다 <b>먼저</b> 소진된다.
     *
     * <p>⚠️ <b>이 열거의 한계가 실제로 현실이 됐다.</b> 종료 경로가 둘(수명 상한·생존 확인) 늘었을 때
     * 여기 한 줄씩 적지 않으면 아무도 묻지 않는다 — 리뷰어가 그 누락을 잡아 줘서 알았다. 열거를
     * 유지하는 동안은 종료 경로를 더할 때마다 이 자리를 함께 봐야 하고, 그게 부담이 되면
     * {@code forEachInRoom}·{@code forEachSessionOf}가 예산을 만들어 넘기는 쪽으로 구조를 바꿔
     * 열거 자체를 없애는 것이 맞다.
     */
    private void assertCloseOutlastsTheEmitBudget(String sessionId, Runnable closeAction,
                                                  CloseStatus expected) throws Exception {
        Lingering consumer = subscribeLingering(sessionId, LINGER_BEYOND_BUDGET);

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread contender = contenderThread(() -> {
            consumer.awaitInsideOnNext();
            closeAction.run();                     // complete 를 경합 구간에 떨어뜨린다
        }, failure);

        contender.start();
        registry.sendToSession(sessionId, out("SYSTEM")).block(VERIFY_CEILING);
        contender.join(TimeUnit.SECONDS.toMillis(30));

        // 종료 예산이 20ms를 넘겨 줬으므로 데이터 채널이 곱게 닫힌다. emit 예산으로 바꾸면 5ms에
        // 소진돼 complete 가 경합으로 실패하고, 그러면 이 래치는 영영 내려오지 않는다 —
        // 제어 채널이 세션은 닫지만 버퍼 잔여분은 버려진다.
        assertThat(failure.get()).as("경합 스레드가 창에 들어가지 못했다").isNull();
        assertThat(consumer.awaitDataChannelClose(Duration.ofSeconds(5)))
                .as("종료 신호가 경합에 소진돼 데이터 채널이 닫히지 않았다")
                .isTrue();
        assertThat(registry.closeStatusOf(sessionId)).isEqualTo(expected);
    }

    @Test
    @DisplayName("전송 경로 강퇴 종료는 emit보다 넉넉한 예산을 쓴다")
    void terminate_kicked_close_outlasts_the_emit_budget() throws Exception {
        registry.register("s1", session(roomId, userId)).block(VERIFY_CEILING);
        assertCloseOutlastsTheEmitBudget("s1", () -> registry.terminateKicked("s1"),
                CloseStatus.POLICY_VIOLATION);
    }

    @Test
    @DisplayName("생존 시계 — 프레임이 올라오면 밀리고, 기한이 지나면 응답 없음으로 답한다")
    void liveness_clock_advances_on_inbound_and_expires_on_silence() {
        // given: 등재 직후는 응답 없음이 아니다 — 방금 핸드셰이크를 마쳤다
        registry.register("s1", session(roomId, userId)).block(VERIFY_CEILING);
        Duration deadline = Duration.ofSeconds(90);
        assertThat(registry.isUnresponsive("s1", deadline))
                .as("등재 직후인데 응답 없음으로 답했다 — 모든 새 세션이 첫 점검에서 끊긴다").isFalse();

        // when: 기한만큼 조용했던 상태를 만든다
        registry.expireLiveness("s1", deadline);

        // then
        assertThat(registry.isUnresponsive("s1", deadline))
                .as("기한이 지났는데 살아 있다고 답했다 — 끊긴 소켓이 수명 상한까지 남는다").isTrue();

        // and: 프레임 하나가 올라오면 시계가 밀려 다시 살아 있는 것으로 답한다
        registry.recordInbound("s1");
        assertThat(registry.isUnresponsive("s1", deadline))
                .as("프레임이 올라왔는데 응답 없음으로 답했다 — 멀쩡한 세션이 끊긴다").isFalse();
    }

    @Test
    @DisplayName("생존 시계 — 모르는 세션은 응답 없음이 아니다(이미 사라진 것을 또 닫지 않는다)")
    void liveness_clock_treats_unknown_session_as_alive() {
        assertThat(registry.isUnresponsive("nope", Duration.ofSeconds(1)))
                .as("사라진 세션을 응답 없음으로 답하면 호출자가 없는 세션을 또 닫으려 든다").isFalse();
    }

    @Test
    @DisplayName("전송 경로 방 종료도 emit보다 넉넉한 예산을 쓴다 — 종료 호출지가 모두 걸려야 한다")
    void terminate_room_ended_close_outlasts_the_emit_budget() throws Exception {
        registry.register("s1", session(roomId, userId)).block(VERIFY_CEILING);
        assertCloseOutlastsTheEmitBudget("s1", () -> registry.terminateRoomEnded("s1"),
                CloseStatus.NORMAL);
    }

    @Test
    @DisplayName("수명 상한 종료도 emit보다 넉넉한 예산을 쓴다")
    void terminate_lifetime_expired_close_outlasts_the_emit_budget() throws Exception {
        registry.register("s1", session(roomId, userId)).block(VERIFY_CEILING);
        assertCloseOutlastsTheEmitBudget("s1", () -> registry.terminateLifetimeExpired("s1"),
                CloseStatus.NORMAL);
    }

    @Test
    @DisplayName("생존 확인 종료도 emit보다 넉넉한 예산을 쓴다")
    void terminate_unresponsive_close_outlasts_the_emit_budget() throws Exception {
        registry.register("s1", session(roomId, userId)).block(VERIFY_CEILING);
        assertCloseOutlastsTheEmitBudget("s1", () -> registry.terminateUnresponsive("s1"),
                CloseStatus.NORMAL);
    }

    @Test
    @DisplayName("강퇴 팬아웃 종료도 emit보다 넉넉한 예산을 쓴다 — 예산을 나눠 쓰므로 단건보다 먼저 마른다")
    void close_user_fanout_outlasts_the_emit_budget() throws Exception {
        registry.register("s1", session(roomId, userId)).block(VERIFY_CEILING);
        assertCloseOutlastsTheEmitBudget("s1", () -> registry.closeUser(roomId, userId).block(VERIFY_CEILING),
                CloseStatus.POLICY_VIOLATION);
    }

    @Test
    @DisplayName("밴 팬아웃 종료도 emit보다 넉넉한 예산을 쓴다")
    void close_user_everywhere_outlasts_the_emit_budget() throws Exception {
        registry.register("s1", session(roomId, userId)).block(VERIFY_CEILING);
        assertCloseOutlastsTheEmitBudget("s1", () -> registry.closeUserEverywhere(userId).block(VERIFY_CEILING),
                CloseStatus.POLICY_VIOLATION);
    }

    @Test
    @DisplayName("방 종료 팬아웃도 emit보다 넉넉한 예산을 쓴다 — 매 방송마다 도는 정상 경로다")
    void close_all_outlasts_the_emit_budget() throws Exception {
        registry.register("s1", session(roomId, userId)).block(VERIFY_CEILING);
        assertCloseOutlastsTheEmitBudget("s1", () -> registry.closeAll(roomId).block(VERIFY_CEILING),
                CloseStatus.NORMAL);
    }

    @Test
    @DisplayName("팬아웃 호출지는 모두 예산 하나를 나눠 쓴다 — 세션마다 새로 주면 상한이 세션 수만큼 곱해진다")
    void every_fanout_entry_point_shares_one_budget() {
        // given: 한 유저의 세션 여럿. 전원 같은 userId 라 계정 단위 팬아웃도 이 세션들을 훑는다.
        //
        // 한때 <b>한 방에</b> 40 세션을 같은 userId 로 넣었는데, 동시 세션 상한
        // (maxSessionsPerUserPerRoom)이 생긴 뒤로 그 모양은 운영에서 성립하지 않는다. 그래서
        // 같은 유저를 여러 방에 퍼뜨린다 — 계정 단위 팬아웃(sendToUserEverywhere·closeUserEverywhere)은
        // 방을 가리지 않아 여전히 40 을 전부 훑고, 방 단위 팬아웃은 그 방의 상한만큼을 훑는다.
        // 세는 것이 예산 <b>개수</b>라 두 축 다 세션이 복수이면 회귀가 잡힌다(호출당 1 vs 세션 수).
        int rooms = 8;
        // 상한을 1 로 내리면 방 축 순회가 1 이 되어 "호출당 예산 1개" 되돌림이 1==1 로
        // 조용히 통과한다 — 이빨이 사라지는 것을 상수 변경이 결정하지 않게 바닥을 둔다.
        int perRoom = Math.max(2, ChatSessionRegistry.DEFAULT_MAX_SESSIONS_PER_USER_PER_ROOM);
        for (int r = 0; r < rooms; r++) {
            UUID room = r == 0 ? roomId : UUID.randomUUID();
            for (int i = 0; i < perRoom; i++) {
                String sid = "s" + r + "-" + i;
                registry.register(sid, session(room, userId)).block(VERIFY_CEILING);
                registry.outbound(sid).subscribe(m -> { });
            }
        }

        // when & then: 팬아웃 호출지는 저마다 예산을 만든다. 하나라도 세션마다 새로 주는 쪽으로
        // 돌아가면 재시도 상한이 세션 수에 비례해 곱해지는데(40세션이면 5ms가 최악 200ms로),
        // 그 자리가 방 전체 중계를 담당하는 Redis 구독 스레드다. 되돌려도 드롭은 줄거나 같으므로
        // 결과를 보는 단언으로는 이 회귀가 잡히지 않는다 — 그래서 생성 수를 직접 센다.
        //
        // <b>호출지를 열거하는 것이 요점이다.</b> 한 곳만 덮으면 나머지 다섯은 아무 테스트도
        // 깨뜨리지 않고 바뀐다. 전송이 먼저, 종료가 나중인 순서인 것은 종료가 세션을 끊기 때문이다 —
        // 예산 자체는 순회 전에 무조건 만들어지므로 세션이 남아 있지 않아도 세어지지만, 순서를
        // 지켜 두면 각 호출이 실제로 세션을 훑는 상태에서 세어진다.
        assertOneBudgetPerCall("sendToRoomLocal", () -> registry.sendToRoomLocal(roomId, out("SYSTEM")).block(VERIFY_CEILING));
        assertOneBudgetPerCall("sendToRoomGated", () -> registry.sendToRoomGated(roomId, s -> out("SYSTEM")).block(VERIFY_CEILING));
        assertOneBudgetPerCall("sendToUserEverywhere", () -> registry.sendToUserEverywhere(userId, out("SYSTEM")).block(VERIFY_CEILING));
        assertOneBudgetPerCall("closeUser", () -> registry.closeUser(roomId, userId).block(VERIFY_CEILING));
        assertOneBudgetPerCall("closeUserEverywhere", () -> registry.closeUserEverywhere(userId).block(VERIFY_CEILING));
        assertOneBudgetPerCall("closeAll", () -> registry.closeAll(roomId).block(VERIFY_CEILING));
    }

    private void assertOneBudgetPerCall(String what, Runnable fanOut) {
        long before = registry.budgetsCreated();
        fanOut.run();
        assertThat(registry.budgetsCreated() - before)
                .as("%s 1회가 만든 예산 수", what)
                .isEqualTo(1);
    }

    @Test
    @DisplayName("방 팬아웃과 ack가 겹쳐도 조용히 사라지거나 겹쳐 오지 않는다")
    void room_fanout_with_concurrent_acks_accounts_for_every_message() throws Exception {
        // given: 한 방에 세션 여럿. 운영에서도 브로드캐스트(Redis 구독 스레드)가 방 전체에 뿌리는 동안
        // 각 세션의 ack(WS 이벤트루프)가 같은 Sink로 들어온다. 위 두 테스트가 경합 처분을 결정적으로
        // 고정하고, 이 테스트는 그 처분이 <b>방 규모의 라우팅</b>에서도 어긋나지 않는지를 본다.
        //
        // 이 규모가 예산을 조이지는 <b>않는다</b>. 60세션 팬아웃 1회가 0.100ms로 5ms 예산의 2%이고,
        // 재시도를 지웠을 때의 드롭률이 단건 55~72%인데 여기선 0.29~0.69%다 — 2스레드가 60개 Sink에
        // 흩어지므로 같은 Sink에서 마주칠 확률이 낮아, 팬아웃이 오히려 예산 희소성에 100배 덜 민감하다
        // (리뷰어 실측). 예산 공유 자체는 하중으로 볼 수 없어 바로 위 테스트가 생성 수로 직접 센다.
        int sessions = 60;
        int broadcasts = 40;
        int acksPerSession = 40;
        List<List<OutboundMessage>> inboxes = new ArrayList<>();
        List<String> sessionIds = new ArrayList<>();
        for (int i = 0; i < sessions; i++) {
            String sid = "s" + i;
            sessionIds.add(sid);
            registry.register(sid, session(roomId, UUID.randomUUID())).block(VERIFY_CEILING);
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
                if (!start.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("시작 래치가 30초 안에 열리지 않았다 — 주 스레드가 countDown 전에 터졌다. 상한이 없으면 이 스레드가 영원히 블록된 채 남아 같은 JVM 의 뒤따르는 테스트를 오염시킨다");
                }
                for (int i = 0; i < broadcasts; i++) {
                    // SYSTEM 프레임을 싣는다 — 운영에서 sendToRoomLocal의 호출자는 SystemMessageService
                    // 하나뿐이고 PII 없는 SYSTEM만 보낸다. NORMAL 방 팬아웃은 전부 sendToRoomGated를
                    // 타고, 이메일·원문 게이트가 거기에만 있다. 여기에 NORMAL을 써 두면 이 모양을 복사한
                    // 다음 사람이 게이트 없는 경로로 특권 뷰를 방 전원에게 보낸다(ArchUnit은 테스트
                    // 소스를 보지 않아 막지 못한다).
                    registry.sendToRoomLocal(roomId, out("SYSTEM")).block(VERIFY_CEILING);
                }
            } catch (Throwable t) {
                failures.add(t);
            } finally {
                done.countDown();
            }
        }).start();
        new Thread(() -> {
            try {
                if (!start.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("시작 래치가 30초 안에 열리지 않았다 — 주 스레드가 countDown 전에 터졌다. 상한이 없으면 이 스레드가 영원히 블록된 채 남아 같은 JVM 의 뒤따르는 테스트를 오염시킨다");
                }
                for (int i = 0; i < acksPerSession; i++) {
                    for (String sid : sessionIds) {
                        registry.sendToSession(sid, out("ACK")).block(VERIFY_CEILING);
                    }
                }
            } catch (Throwable t) {
                failures.add(t);
            } finally {
                done.countDown();
            }
        }).start();
        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS))
                .as("브로드캐스트·ack 스레드 중 하나가 60초 안에 끝나지 않았다")
                .isTrue();
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
        // 가고 SYSTEM 하나가 드롭으로 세어진 경우가 가려진다.
        //
        // 단언은 받은함 내용이 아니라 <b>크기</b>로 한다. 오라우팅 뮤턴트에서는 한 세션에 수천 건이
        // 쌓이는데, 내용으로 비교하면 실패 리포트가 그 전문을 쏟아내 정작 어긋난 수치를 묻는다.
        long totalReceived = inboxes.stream().mapToLong(List::size).sum();
        assertThat(totalReceived + registry.droppedOnContention())
                .as("받은 수 + 센 드롭")
                .isEqualTo((long) sessions * (broadcasts + acksPerSession));
        for (int i = 0; i < inboxes.size(); i++) {
            List<OutboundMessage> inbox = inboxes.get(i);
            String where = "세션 s" + i;
            assertThat(inbox.size()).as("%s 총 수신", where)
                    .isLessThanOrEqualTo(broadcasts + acksPerSession);
            assertThat(inbox.stream().filter(m -> "ACK".equals(m.type())).count())
                    .as("%s — 남의 세션 ack가 섞였다", where).isLessThanOrEqualTo(acksPerSession);
            assertThat(inbox.stream().filter(m -> "SYSTEM".equals(m.type())).count())
                    .as("%s — 브로드캐스트가 겹쳐 왔다", where).isLessThanOrEqualTo(broadcasts);
        }
    }

    @Test
    @DisplayName("구독 전에 보낸 프레임도 도착한다 — 등재와 구독 사이의 창을 버퍼가 받아 준다")
    void emit_before_subscribe_is_not_lost() {
        // given: 핸들러는 세션을 등재한 뒤에 아웃바운드를 구독한다. 그 사이에 들어온 프레임이
        // 버려지면 입장 직후의 ROOM_INFO·SYSTEM이 조용히 사라진다.
        registry.register("s1", session(roomId, userId)).block(VERIFY_CEILING);

        // when: 아직 구독 전인데 보낸다
        registry.sendToSession("s1", out("ROOM_INFO")).block(VERIFY_CEILING);

        // then: 구독이 붙는 순간 받는다
        List<OutboundMessage> received = new CopyOnWriteArrayList<>();
        registry.outbound("s1").subscribe(received::add);
        assertThat(received).extracting(OutboundMessage::type).containsExactly("ROOM_INFO");
    }

    @Test
    @DisplayName("아웃바운드 버퍼 초과 — 느린 클라 세션을 끊는다(Pod 힙 보호)")
    void outbound_overflow_closes_session() {
        // given: 구독하되 소비하지 않는(request 0) 느린 클라
        registry.register("s1", session(roomId, userId)).block(VERIFY_CEILING);

        // when: 버퍼 용량의 2배를 밀어넣는다
        // then: 버퍼 크기만큼만 담기고, 무제한 적체 대신 스트림이 종료된다(= 세션 끊김)
        StepVerifier.create(registry.outbound("s1"), 0)
                .then(() -> {
                    for (int i = 0; i < ChatSessionRegistry.OUTBOUND_BUFFER_SIZE * 2; i++) {
                        registry.sendToSession("s1", out("NORMAL")).block(VERIFY_CEILING);
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
        registry.register("overflowing", session(roomId, userId)).block(VERIFY_CEILING);
        registry.register("kicked", session(roomId, otherUserId)).block(VERIFY_CEILING);

        // when
        StepVerifier.create(registry.outbound("overflowing"), 0)
                .then(() -> {
                    for (int i = 0; i < ChatSessionRegistry.OUTBOUND_BUFFER_SIZE * 2; i++) {
                        registry.sendToSession("overflowing", out("NORMAL")).block(VERIFY_CEILING);
                    }
                })
                .thenCancel()
                .verify(VERIFY_CEILING);
        registry.closeUser(roomId, otherUserId).block(VERIFY_CEILING);

        // then
        assertThat(registry.closeStatusOf("overflowing")).isEqualTo(CloseStatus.SERVICE_OVERLOAD);
        assertThat(registry.closeStatusOf("kicked")).isEqualTo(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    @DisplayName("레이트리밋 창 — 걸린 뒤에는 남은 시간이 나오고, 지나면 다시 0이 된다")
    void rate_limit_window_reports_remaining_then_clears() {
        // given
        registry.register("s1", session(roomId, userId)).block(VERIFY_CEILING);
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
        registry.register("s1", session(roomId, userId)).block(VERIFY_CEILING);

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
        registry.register("s1", session(roomId, userId)).block(VERIFY_CEILING);

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
        registry.register("s1", session(roomId, userId)).block(VERIFY_CEILING);

        // when
        registry.recordRateLimited("s1", 3);

        // then: 즉시 읽어도 3이 나와야 한다 — 절삭이면 2로 떨어진다
        assertThat(registry.rateLimitRetryAfterSeconds("s1")).isEqualTo(3L);
    }

    @Test
    @DisplayName("레이트리밋 창 — 등록 직후에는 제한이 없다(nanoTime 원점이 음수여도)")
    void rate_limit_window_is_clear_right_after_register() {
        // given & when
        registry.register("s1", session(roomId, userId)).block(VERIFY_CEILING);

        // then: 0을 센티널로 쓰면 nanoTime이 음수인 JVM에서 전 세션이 막힌다
        assertThat(registry.rateLimitRetryAfterSeconds("s1")).isZero();
    }

    @Test
    @DisplayName("시청자 수 — 창 안의 재조회는 Redis로 가지 않는다(입장이 방 크기에 비례해 비싸지면 안 된다)")
    void activeCount_servedFromCacheWithinWindow() {
        // given: 실제 흐름대로 등록 뒤에 묻는다 — 캐시는 이 Pod에 남아 있는 방에만 붙는다
        // (조회를 다녀온 사이 방이 비면 담지 않는다. 담으면 회수 주체가 사라져 항목이 영영 남는다.)
        registry.register("s1", session(roomId, UUID.randomUUID())).block(VERIFY_CEILING);
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
        registry.register("s1", session(roomId, UUID.randomUUID())).block(VERIFY_CEILING);
        given(sessionRepository.count(roomId))
                .willReturn(Mono.error(new RuntimeException("redis down")))
                .willReturn(Mono.just(7L));

        // when & then: 첫 번째는 실패하고, 두 번째는 다시 물어서 값을 얻는다
        StepVerifier.create(registry.getActiveCount(roomId)).expectError(RuntimeException.class).verify(VERIFY_CEILING);
        StepVerifier.create(registry.getActiveCount(roomId)).expectNext(7L).expectComplete()
                .verify(java.time.Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("방 종료 재확인 창 — 등록 직후에는 묻지 않는다(입장 게이트가 방금 확인했다)")
    void roomAliveRecheck_notDueRightAfterRegister() {
        // given
        UUID room = UUID.randomUUID();
        registry.register("s1", session(room, UUID.randomUUID())).block(VERIFY_CEILING);

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
        registry.register("s1", session(room, UUID.randomUUID())).block(VERIFY_CEILING);
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
        registry.register("s1", session(room, UUID.randomUUID())).block(VERIFY_CEILING);
        StepVerifier.create(registry.getActiveCount(room)).expectNext(1L).expectComplete()
                .verify(java.time.Duration.ofSeconds(5));
        registry.unregister(room, "s1").block(VERIFY_CEILING);

        // when: 같은 방에 다시 들어온다
        registry.register("s2", session(room, UUID.randomUUID())).block(VERIFY_CEILING);

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
            registry.sendToSession(gone, out("SYSTEM")).block(VERIFY_CEILING);
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
        registry.register("s1", session(room, UUID.randomUUID())).block(VERIFY_CEILING);

        // when & then: 서버가 정한 사유가 없으면 정책 위반 코드를 붙이면 안 된다 — 프론트가 재접속을 포기한다
        assertThat(registry.closeStatusOf("s1")).isEqualTo(CloseStatus.NORMAL);
        assertThat(registry.closeStatusOf("모르는세션")).isEqualTo(CloseStatus.NORMAL);
    }

    @Test
    @DisplayName("강퇴로 끊긴 세션은 1008로 닫힌다 — 프론트는 이 코드로 재접속 금지를 판단한다")
    void closeStatus_reflectsKick() {
        // given
        UUID room = UUID.randomUUID();
        registry.register("s1", session(room, UUID.randomUUID())).block(VERIFY_CEILING);

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
        registry.register("s1", session(room, UUID.randomUUID())).block(VERIFY_CEILING);
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
        registry.register("s1", session(room, UUID.randomUUID())).block(VERIFY_CEILING);

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

    /**
     * ⭐ 같은 방의 동시 조회가 <b>한 번의 왕복으로</b> 합쳐진다.
     *
     * <p>캐시만으로는 메워지지 않는 창이 있다 — 첫 조회가 Redis 를 다녀오는 동안 도착한 호출은 전부
     * 미스라 각자 왕복한다. 평상시에는 ms 짜리 창이라 중복이 몇 건이지만, 파드 kill 후 재접속
     * 폭풍에서는 그 창에 그 방의 세션 수만큼이 들어온다 — Redis 가 이미 부하를 받는 순간에 HVALS 가
     * N 배로 간다.
     */
    @Test
    @DisplayName("⭐ 같은 방의 동시 조회는 한 번만 Redis 에 간다 — 재접속 폭풍이 왕복을 N배로 만들지 않게")
    void concurrent_lookups_share_one_round_trip() {
        // given: 왕복이 100ms 걸리는 조회 — 그 사이에 50건이 더 들어온다
        UUID room = UUID.randomUUID();
        registry.register("s1", session(room, UUID.randomUUID())).block(VERIFY_CEILING);
        given(sessionRepository.count(room))
                .willReturn(Mono.just(7L).delayElement(Duration.ofMillis(100)));

        // when: 50건을 동시에 구독한다
        List<Mono<Long>> lookups = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            lookups.add(registry.getActiveCount(room));
        }

        // then: 전부 같은 값을 받고
        StepVerifier.create(reactor.core.publisher.Flux.merge(lookups))
                .expectNextCount(50)
                .expectComplete()
                .verify(VERIFY_CEILING);

        // Redis 는 한 번만 다녀왔다. 합치지 않으면 50번 간다(캐시는 첫 응답 뒤에야 채워진다).
        then(sessionRepository).should(times(1)).count(room);
    }

    /**
     * ⭐ 비행 제거가 <b>자기 항목만</b> 걷는다 — 그 사이 설치된 항목을 걷어내지 않는다.
     *
     * <p>한때 이 보장을 "테스트로 가를 수 없다"고 적었다. 틀렸다 — 재야 할 것이 인터리빙이 아니라
     * <b>제거의 조건절</b>이라서, 비행이 진행 중인 동안 맵 값을 바꿔 두면 그 조건이 그대로 드러난다.
     * 동시성도 래치도 필요 없다 — 한 스레드 순차다.
     *
     * <p>맵을 리플렉션으로 <b>집어 와서 내용을 바꾼다</b>(필드 자체는 쓰지 않는다). 아래 헬퍼가
     * "읽기 전용" 이 아니라는 뜻이니, 재사용할 때 운영 자료구조를 건드리는 것임을 알고 쓸 것.
     * 그리고 "운영 코드에 테스트 훅이 필요 없다" 가 아니다 — {@code trackedInFlightCounts()} 라는
     * 훅은 이미 있다. 그걸로 안 되는 이유는 <b>크기만으로는 어느 항목인지 가를 수 없어서</b>다.
     *
     * <p>두 인자 {@code remove(key, value)} 면 값이 달라 아무것도 지우지 않는다. 한 인자였다면
     * "그 방의 비행 자리를 비운다"가 되어 센티넬이 걷히고, 운영에서는 그 자리에 방금 설치된 남의
     * 비행이 들어 있다 — 부하가 가장 높은 순간에 병합이 중복 왕복으로 역전된다.
     */
    @Test
    @DisplayName("⭐ 비행 제거가 자기 항목만 걷는다 — 그 사이 설치된 항목을 걷어내지 않는다")
    void the_flight_removal_evicts_only_its_own_entry() throws Exception {
        // given: 종결 시점을 테스트가 쥔 상류
        UUID room = UUID.randomUUID();
        registry.register("s1", session(room, UUID.randomUUID())).block(VERIFY_CEILING);
        Sinks.One<Long> upstream = Sinks.one();
        given(sessionRepository.count(room)).willReturn(upstream.asMono());

        reactor.core.Disposable subscription = registry.getActiveCount(room).subscribe();
        java.util.Map<UUID, Mono<Long>> inFlight = inFlightCounts();
        assertThat(inFlight).as("구독으로 비행이 설치됐어야 한다").containsKey(room);

        // when: 비행이 아직 pending 인 동안 그 방 키를 다른 Mono 로 바꿔 둔다
        //       — 운영에서 "앞 비행이 끝나기 전에 설치된 새 비행"에 해당한다
        Mono<Long> sentinel = Mono.just(99L);
        inFlight.put(room, sentinel);

        // 상류를 종결시켜 제거를 발화시킨다(doFinally 가 이 스레드에서 동기로 돈다)
        upstream.tryEmitValue(7L);

        // then
        assertThat(inFlight.get(room))
                .as("제거가 자기 항목이 아닌 것을 걷어냈다 — 값 비조건부 remove 로 되돌아갔다")
                .isSameAs(sentinel);
        subscription.dispose();
    }

    /**
     * ⭐ 합류 지점이 취소를 <b>상류로 보내지 않는다</b> — {@code cache()} 가 지는 보장이다.
     *
     * <p>{@code getActiveCount} javadoc 이 "취소에도 지워지지만 즉시가 아니다 — 취소가 상류로
     * 전파되지 않아 왕복이 끝날 때 {@code onComplete} 로 지워진다" 를 보장으로 내세운다. 그 보장이
     * 참인 이유는 전적으로 {@code cache()} 다 — {@code share()} 로 바꾸면 refCount 0 에서 취소를
     * 상류로 <b>즉시</b> 전파한다. 그러면 먼저 붙은 호출자 하나가 떠나는 것만으로 그 방의 왕복이
     * 취소되고, 뒤에 합류한 호출자는 새 왕복을 열어야 한다 — 병합이 하려던 일의 반대다.
     *
     * <p>그 차이가 앞의 센티넬 테스트로는 갈리지 않는다(실측: {@code share()} 뮤턴트가 생존).
     * 상류 취소를 직접 관측해야 갈린다.
     */
    @Test
    @DisplayName("⭐ 합류 지점이 취소를 상류로 보내지 않는다 — 떠나는 호출자가 남의 왕복을 끊지 않게")
    void cancelling_a_joiner_does_not_cancel_the_shared_round_trip() {
        // given: 상류 취소를 관측할 수 있는 조회
        UUID room = UUID.randomUUID();
        registry.register("s1", session(room, UUID.randomUUID())).block(VERIFY_CEILING);
        java.util.concurrent.atomic.AtomicBoolean upstreamCancelled =
                new java.util.concurrent.atomic.AtomicBoolean();
        Sinks.One<Long> upstream = Sinks.one();
        given(sessionRepository.count(room))
                .willReturn(upstream.asMono().doOnCancel(() -> upstreamCancelled.set(true)));

        // when: 유일한 구독자가 떠난다
        reactor.core.Disposable subscription = registry.getActiveCount(room).subscribe();
        subscription.dispose();

        // then: 상류는 계속 간다 — 그래야 뒤에 합류한 호출자가 같은 왕복을 받는다
        assertThat(upstreamCancelled.get())
                .as("취소가 상류로 전파됐다 — 떠나는 호출자가 그 방의 왕복을 끊는다").isFalse();
    }

    /**
     * 진행 중인 비행 맵을 집어 온다.
     *
     * <p>⚠️ <b>읽기 전용이 아니다.</b> 필드는 쓰지 않지만 돌려주는 것은 운영 맵 그 자체이므로,
     * 호출자가 {@code put}·{@code remove} 를 하면 운영 자료구조가 바뀐다. 지금 유일한 사용처가
     * 그 변형을 의도적으로 한다({@code put} 으로 "남의 비행" 을 만든다).
     */
    @SuppressWarnings("unchecked")
    private java.util.Map<UUID, Mono<Long>> inFlightCounts() throws Exception {
        java.lang.reflect.Field field = ChatSessionRegistry.class.getDeclaredField("inFlightCounts");
        field.setAccessible(true);
        return (java.util.Map<UUID, Mono<Long>>) field.get(registry);
    }

    /**
     * ⭐ 끝난 조회가 비행 자리를 놓는다 — 두 인자 {@code remove(key, value)} 가 실제로 맞는지.
     *
     * <p>{@code doFinally} 가 {@code cache()} <b>상류</b>에 붙어 있어, 그 람다가 만들어지는 시점에는
     * 맵에 저장될 참조({@code cache()} 적용 <b>후</b>)가 아직 없다. 람다는 {@code AtomicReference} 를
     * 캡처하고 <b>호출 시점</b>에 읽으므로 맞는 참조를 보지만, 그게 틀리면 {@code remove(key, value)} 가
     * 영구 no-op 이 되어 <b>항목이 절대 제거되지 않는다</b> — 고치려던 것(남의 비행을 걷어냄)보다 나쁜
     * 누수다. 그 자리를 직접 고정한다.
     */
    @Test
    @DisplayName("⭐ 끝난 조회가 비행 자리를 놓는다 — 두 인자 remove 가 맵의 값과 맞아야 한다")
    void a_completed_lookup_releases_the_flight() {
        // given
        UUID room = UUID.randomUUID();
        registry.register("s1", session(room, UUID.randomUUID())).block(VERIFY_CEILING);
        given(sessionRepository.count(room)).willReturn(Mono.just(7L));

        // when
        StepVerifier.create(registry.getActiveCount(room)).expectNext(7L).expectComplete()
                .verify(VERIFY_CEILING);

        // then: 비행이 끝났으면 자리가 비어 있어야 한다. self 가 맵의 값과 다른 참조를 담으면
        // remove(key, value) 가 아무것도 지우지 않아 여기서 1이 남는다.
        assertThat(registry.trackedInFlightCounts())
                .as("끝난 비행의 자리가 남았다 — remove(key, value) 가 맵의 값과 맞지 않는다").isZero();
    }

    /**
     * ⭐ 취소된 조회도 비행 자리를 놓는다.
     *
     * <p>입장 중에 클라가 끊기면 그 체인이 취소된다 — 운영에서 도달하는 경로다. 자리가 안 풀리면
     * 그 방의 다음 조회가 끝난 비행에 영구히 합류해 시청자 수가 얼어붙는다.
     *
     * <p>취소 <b>즉시</b>가 아니라 왕복이 끝날 때 풀린다 — {@code cache()} 가 취소를 상류로 보내지
     * 않으므로 상류는 끝까지 가고 그 {@code onComplete} 에서 {@code doFinally} 가 돈다. 그래서
     * 단언이 폴링이다(고정 대기는 부하에서 흔들린다).
     */
    @Test
    @DisplayName("⭐ 취소된 조회도 비행 자리를 놓는다 — 안 놓으면 그 방 시청자 수가 얼어붙는다")
    void a_cancelled_lookup_still_releases_the_flight() {
        // given
        UUID room = UUID.randomUUID();
        registry.register("s1", session(room, UUID.randomUUID())).block(VERIFY_CEILING);
        given(sessionRepository.count(room))
                .willReturn(Mono.just(7L).delayElement(Duration.ofMillis(100)));

        // when: 조회를 걸고 바로 취소한다
        reactor.core.Disposable subscription = registry.getActiveCount(room).subscribe();
        assertThat(registry.trackedInFlightCounts()).as("구독으로 비행이 생겼어야 한다").isEqualTo(1);
        subscription.dispose();

        // then: 왕복이 끝나면 자리가 풀린다
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (registry.trackedInFlightCounts() != 0 && System.nanoTime() - deadline < 0) {
            java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
        }
        assertThat(registry.trackedInFlightCounts())
                .as("취소된 조회의 비행 자리가 5초 안에 풀리지 않았다").isZero();
    }

    @Test
    @DisplayName("⭐ 조립만으로는 비행이 생기지 않는다 — 구독을 늦추는 호출자가 남의 자리를 잡지 않게")
    void assembling_a_lookup_does_not_install_a_flight() {
        // given
        UUID room = UUID.randomUUID();
        registry.register("s1", session(room, UUID.randomUUID())).block(VERIFY_CEILING);
        given(sessionRepository.count(room)).willReturn(Mono.just(7L));

        // when: Mono 를 받아만 두고 구독하지 않는다
        registry.getActiveCount(room);

        // then: 자리도 안 잡고 Redis 도 안 간다. defer 가 없으면 조립 시점에 둘 다 일어난다.
        assertThat(registry.trackedInFlightCounts()).as("조립만으로 비행 자리를 잡았다").isZero();
        then(sessionRepository).should(never()).count(room);
    }

    @Test
    @DisplayName("시청자 수 캐시 — 조회 중에 방이 비면 담지 않는다(회수 주체가 사라진 뒤 생긴 항목은 영영 남는다)")
    void activeCount_doesNotCacheWhenRoomEmptiedDuringLookup() {
        // given: Redis를 다녀오는 사이에 마지막 세션이 끊기는 상황.
        // 담는 시점과 회수 시점이 다른 락 구간이라, 그냥 넣으면 unregister가 먼저 지나간 뒤 항목이 새로 생긴다.
        UUID room = UUID.randomUUID();
        registry.register("s1", session(room, UUID.randomUUID())).block(VERIFY_CEILING);
        given(sessionRepository.count(room))
                .willReturn(Mono.just(5L).delayElement(Duration.ofMillis(60)))
                .willReturn(Mono.just(9L));

        // when: 조회를 걸어두고 그 사이에 방을 비운다
        Mono<Long> inFlight = registry.getActiveCount(room);
        StepVerifier.create(inFlight.doOnSubscribe(sub -> {
                    registry.unregister(room, "s1").block(VERIFY_CEILING);   // 조회 응답 전에 방이 빈다
                }))
                .expectNext(5L)
                .expectComplete()
                .verify(java.time.Duration.ofSeconds(5));

        // then: 그 방에 다시 들어오면 캐시가 아니라 Redis를 봐야 한다.
        // 고아 항목이 남았다면 아래가 9가 아니라 5로 나온다.
        registry.register("s2", session(room, UUID.randomUUID())).block(VERIFY_CEILING);
        StepVerifier.create(registry.getActiveCount(room)).expectNext(9L).expectComplete()
                .verify(java.time.Duration.ofSeconds(5));
    }
}
