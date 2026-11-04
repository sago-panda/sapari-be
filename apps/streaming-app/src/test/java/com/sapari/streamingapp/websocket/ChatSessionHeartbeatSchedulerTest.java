package com.sapari.streamingapp.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.chat.application.port.ChatMetrics;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.model.ChatSession;
import com.sapari.chat.domain.repository.ChatSessionRepository;
import com.sapari.chat.domain.rule.ChatPermissionPolicy;

import reactor.core.publisher.Mono;

/**
 * Pod 당 하나인 재등록 타이머.
 *
 * <p>재등록이 없으면 세션 명부는 "마지막 입장 이후" 기준으로만 갱신된다 — Pod 가 크래시한 뒤 남은
 * 항목이 키 TTL(24시간)까지 시청자 수를 부풀리고, 한산한 방은 신규 입장이 없어 방송 중에 키가
 * 만료된다. 둘 다 방송 내내 틀린 수가 보이는 모양이다.
 *
 * <p><b>가장 중요한 단언은 실패 격리다.</b> 한 세션의 재등록 실패가 스트림을 끝내면 그 뒤로 이 Pod 의
 * 재등록이 통째로 멈추고, 그러면 살아 있는 시청자 <b>전원</b>이 상한 뒤에 집계에서 사라진다 —
 * 고치려던 것보다 나쁜 결과다.
 */
@DisplayName("세션 재등록 타이머 — Pod 당 하나, 실패는 그 세션만")
class ChatSessionHeartbeatSchedulerTest {

    /** 반응형 종결자(.block() / StepVerifier) 상한 — 없으면 회귀가 테스트를 실패시키는 대신 <b>멈추게</b> 한다(근거: modules/chat/AGENTS.md). */
    private static final Duration VERIFY_CEILING = Duration.ofSeconds(10);

    private ChatSessionRepository sessionRepository;
    private ChatSessionRegistry registry;

    private final UUID roomId = UUID.randomUUID();
    private final UUID userA = UUID.randomUUID();
    private final UUID userB = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        sessionRepository = mock(ChatSessionRepository.class);
        lenient().when(sessionRepository.add(any(), any(), any())).thenReturn(Mono.empty());
        lenient().when(sessionRepository.remove(any(), any())).thenReturn(Mono.empty());
        lenient().when(sessionRepository.count(any())).thenReturn(Mono.just(1L));
        // 주기는 포트가 정한다 — 레지스트리가 자기 값을 쓰면 한쪽을 바꿀 때 다른 쪽이 안 따라간다.
        lenient().when(sessionRepository.heartbeatInterval()).thenReturn(Duration.ofMillis(50));
        registry = new ChatSessionRegistry(sessionRepository, new ChatPermissionPolicy(), ChatMetrics.NOOP);
    }

    private void register(String sessionId, UUID userId) {
        registry.register(sessionId, new ChatSession(roomId, userId, ChatRole.BUYER,
                "닉", "e@example.com", false)).block(VERIFY_CEILING);
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + VERIFY_CEILING.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() - deadline < 0) {
            Thread.onSpinWait();
        }
    }

    /**
     * "일어나지 않는다" 를 확인하려면 기다릴 수밖에 없는데, 그 기다림에는 <b>짧은</b> 상한을 둔다.
     *
     * <p>{@code await(() -> false)} 로 반응형 상한(10초)까지 스핀하면 테스트 하나가 10초가 되고
     * 스위트가 그만큼 느려진다 — 실제로 그렇게 썼다가 22초를 봤다. 주기 다섯 개면 충분하다.
     */
    private static void awaitQuietly() {
        long deadline = System.nanoTime() + Duration.ofMillis(250).toNanos();
        while (System.nanoTime() - deadline < 0) {
            Thread.onSpinWait();
        }
    }

    @Test
    @DisplayName("⭐ 타이머가 로컬 세션 전부를 재등록한다 — 주기는 포트가 정한다")
    void everyLocalSessionIsReregistered() {
        // given
        given(sessionRepository.heartbeat(any(), any(), any())).willReturn(Mono.empty());
        register("s1", userA);
        register("s2", userB);

        // when
        registry.startHeartbeat();
        try {
            await(() -> mockingDetails(sessionRepository, "s1") && mockingDetails(sessionRepository, "s2"));

            // then
            then(sessionRepository).should(atLeast(1)).heartbeat(roomId, "s1", userA);
            then(sessionRepository).should(atLeast(1)).heartbeat(roomId, "s2", userB);
        } finally {
            registry.stopHeartbeat();
        }
    }

    @Test
    @DisplayName("⭐ 한 세션의 실패가 Pod 전체 재등록을 멈추지 않는다 — 멈추면 전원이 집계에서 사라진다")
    void oneFailureDoesNotStopThePod() {
        // given: s1 은 늘 실패하고 s2 는 성공한다
        given(sessionRepository.heartbeat(eq(roomId), eq("s1"), any()))
                .willReturn(Mono.error(new IllegalStateException("Redis down")));
        given(sessionRepository.heartbeat(eq(roomId), eq("s2"), any())).willReturn(Mono.empty());
        register("s1", userA);
        register("s2", userB);

        // when
        registry.startHeartbeat();
        try {
            // then: 실패한 쪽이 먼저여도 성공한 쪽이 <b>여러 주기에 걸쳐</b> 계속 불려야 한다 —
            // 스트림이 끝났다면 한 번에서 멈춘다
            await(() -> registry.heartbeatFailures() >= 2);
            then(sessionRepository).should(atLeast(2)).heartbeat(roomId, "s2", userB);
            assertThat(registry.heartbeatFailures())
                    .as("실패가 세어지지 않았다 — 전 세션 재등록이 멈춘 상태가 조용해진다")
                    .isGreaterThanOrEqualTo(2);
        } finally {
            registry.stopHeartbeat();
        }
    }

    @Test
    @DisplayName("퇴장한 세션은 더 이상 재등록되지 않는다 — 좀비를 우리가 만들지 않게")
    void anUnregisteredSessionIsNotReregistered() {
        // given
        given(sessionRepository.heartbeat(any(), any(), any())).willReturn(Mono.empty());
        register("s1", userA);
        registry.unregister(roomId, "s1").block(VERIFY_CEILING);

        // when
        registry.startHeartbeat();
        try {
            awaitQuietly();

            // then
            then(sessionRepository).should(never()).heartbeat(any(), eq("s1"), any());
        } finally {
            registry.stopHeartbeat();
        }
    }

    @Test
    @DisplayName("stop 하면 더 이상 돌지 않는다 — Pod 종료 뒤 타이머가 남지 않게")
    void stoppingDisposesTheTimer() {
        // given
        given(sessionRepository.heartbeat(any(), any(), any())).willReturn(Mono.empty());
        register("s1", userA);
        registry.startHeartbeat();
        await(() -> mockingDetails(sessionRepository, "s1"));

        // when
        registry.stopHeartbeat();
        int seen = invocationCount(sessionRepository, "s1");

        // then: 멈춘 뒤로는 늘지 않는다
        awaitQuietly();
        assertThat(invocationCount(sessionRepository, "s1")).isEqualTo(seen);
    }

    private static boolean mockingDetails(ChatSessionRepository repository, String sessionId) {
        return invocationCount(repository, sessionId) > 0;
    }

    private static int invocationCount(ChatSessionRepository repository, String sessionId) {
        return (int) org.mockito.Mockito.mockingDetails(repository).getInvocations().stream()
                .filter(invocation -> "heartbeat".equals(invocation.getMethod().getName()))
                .filter(invocation -> sessionId.equals(invocation.getArgument(1)))
                .count();
    }
}
