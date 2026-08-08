package com.sapari.streamingapp.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

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
        given(sessionRepository.add(any(), any(), any())).willReturn(Mono.empty());
        given(sessionRepository.remove(any(), any())).willReturn(Mono.empty());
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
                .verifyComplete();
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
        StepVerifier.create(registry.outbound("s1")).verifyComplete();
    }

    @Test
    @DisplayName("closeAll — 방 세션의 아웃바운드가 완료(complete)된다")
    void close_all_completes_outbound() {
        // given
        registry.register("s1", session(roomId, userId)).block();

        // when & then
        StepVerifier.create(registry.outbound("s1"))
                .then(() -> registry.closeAll(roomId).block())
                .verifyComplete();
    }

    @Test
    @DisplayName("closeUser — 해당 유저 세션의 아웃바운드만 완료")
    void close_user_completes_target_outbound() {
        // given
        registry.register("s1", session(roomId, userId)).block();

        // when
        StepVerifier.create(registry.outbound("s1"))
                .then(() -> registry.closeUser(roomId, userId).block())
                .verifyComplete();

        // then
        assertThat(registry.outbound("unknown")).isNotNull();
    }

    @Test
    @DisplayName("등록 — Redis 명부 등재가 실패해도 접속은 성립한다(전달은 로컬 자료구조로 돈다)")
    void register_survives_redis_failure() {
        // given
        given(sessionRepository.add(any(), any(), any())).willReturn(Mono.error(new RuntimeException("redis down")));

        // when & then
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
        // given
        registry.register("s1", session(roomId, userId)).block();
        given(sessionRepository.remove(any(), any())).willReturn(Mono.error(new RuntimeException("redis down")));

        // when
        StepVerifier.create(registry.unregister(roomId, "s1")).verifyComplete();

        // then
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
        // given
        registry.register("s1", session(roomId, userId)).block();
        // 종료 전에는 사유가 미정이다 — closeStatusOf의 기본값(NORMAL)만 보면 closeAll을 지워도 통과한다
        assertThat(registry.isTerminating("s1")).isFalse();

        registry.closeAll(roomId).block();

        assertThat(registry.isTerminating("s1")).isTrue();

        // when & then
        StepVerifier.create(registry.terminationSignal("s1"))
                .expectNext(CloseStatus.NORMAL)
                .verifyComplete();
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
        StepVerifier.create(registry.getActiveCount(roomId)).expectNext(42L).verifyComplete();
        StepVerifier.create(registry.getActiveCount(roomId)).expectNext(42L).verifyComplete();

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
        StepVerifier.create(registry.getActiveCount(roomId)).expectNext(7L).verifyComplete();
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
        StepVerifier.create(registry.getActiveCount(room)).expectNext(1L).verifyComplete();
        registry.unregister(room, "s1").block();

        // when: 같은 방에 다시 들어온다
        registry.register("s2", session(room, UUID.randomUUID())).block();

        // then: 캐시가 남아 있으면 이전 값이 나오고, 방송이 끝난 방의 항목이 계속 쌓인다
        StepVerifier.create(registry.getActiveCount(room)).expectNext(9L).verifyComplete();
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
        StepVerifier.create(registry.outbound(gone)).verifyComplete();   // 빈 스트림이어야 한다
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
        StepVerifier.create(registry.terminationSignal("s1")).expectNext(CloseStatus.NORMAL).verifyComplete();
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
                .verifyComplete();

        // then: 그 방에 다시 들어오면 캐시가 아니라 Redis를 봐야 한다.
        // 고아 항목이 남았다면 아래가 9가 아니라 5로 나온다.
        registry.register("s2", session(room, UUID.randomUUID())).block();
        StepVerifier.create(registry.getActiveCount(room)).expectNext(9L).verifyComplete();
    }
}
