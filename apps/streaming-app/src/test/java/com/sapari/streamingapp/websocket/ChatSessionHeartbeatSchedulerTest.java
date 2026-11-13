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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.chat.application.port.ChatMetrics;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.model.ChatSession;
import com.sapari.chat.domain.repository.ChatSessionRepository;
import com.sapari.chat.domain.rule.ChatPermissionPolicy;
import com.sapari.streamingapp.config.ChatSessionGaugeConfig;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.springframework.beans.factory.ObjectProvider;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

import org.slf4j.LoggerFactory;

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


    /**
     * 재등록 실패에 <b>미터</b>가 붙는지. 셀 값은 있었고 미터가 없었다.
     *
     * <p>{@code heartbeatFailures} 는 전부터 {@code AtomicLong} 으로 쌓였지만
     * {@code ChatSessionGaugeConfig} 가 등록하는 미터 다섯에 들어 있지 않았고, 그 접근자를 읽는 곳은
     * 이 테스트뿐이었다. 그래서 운영에서는 억눌린 WARN 한 줄이 유일한 신호였다 — 이 파일 머리가
     * 적는 결과("살아 있는 시청자 전원이 상한 뒤에 집계에서 사라진다")가 그 신호 하나에 걸려 있었다.
     *
     * <p>⚠️ <b>이 수가 0 이어도 재등록이 멈춰 있을 수 있다.</b> 회차가 <b>실패</b>하면 세어지지만
     * 아예 <b>안 끝나면</b> 세어지지 않는다 — {@code ChatSessionRegistry} 의 주기 javadoc 이 그 갈래를
     * 적고 있고, 회차 길이를 재는 신호는 아직 없다. 그러니 이 미터는 "재등록이 건강하다" 의 증거가
     * 아니고 "재등록이 실패하고 있다" 의 증거다. 그 둘을 바꿔 읽으면 침묵이 안전으로 읽힌다.
     *
     * <p>이름을 상수가 아니라 <b>리터럴</b>로 쓴다 — 상수는 {@code config} 패키지 전용이고, 더 중요하게는
     * 미터 이름이 발행된 인터페이스라서다({@code infra/AGENTS.md} 의 당직 런북이 인용한다).
     */
    @Test
    @DisplayName("⭐ 재등록 실패에 미터가 붙는다 — 억눌린 WARN 한 줄이 유일한 신호였다")
    void theHeartbeatFailureCounterIsRegisteredAndFollows() {
        // given: 재등록이 늘 실패한다
        given(sessionRepository.heartbeat(any(), any(), any()))
                .willReturn(Mono.error(new IllegalStateException("Redis down")));
        MeterRegistry meters = new SimpleMeterRegistry();
        new ChatSessionGaugeConfig().chatSessionGauges(registry, providing(meters));
        register("s1", userA);

        // when
        registry.startHeartbeat();
        try {
            await(() -> registry.heartbeatFailures() >= 2);
        } finally {
            // 먼저 멈춘다 — 돌고 있는 동안 비교하면 두 읽기 사이에 값이 늘어 플레이크가 된다.
            registry.stopHeartbeat();
        }

        // then: 미터가 없으면 get(...) 에서 먼저 터진다 — 그게 이 칸이 잡는 결함이다.
        assertThat(meters.get("chat.sessions.heartbeat_failures").functionCounter().count())
                .as("미터가 레지스트리 값을 따라오지 않는다 — 형제 접근자에 배선되면 집합 단언은 초록이다")
                .isEqualTo(registry.heartbeatFailures());
        assertThat(registry.heartbeatFailures())
                .as("실패가 한 건도 안 생겼다 — 셀 것이 없으면 이 칸은 아무것도 보증하지 않는다")
                .isGreaterThanOrEqualTo(2);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MeterRegistry> providing(MeterRegistry value) {
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        lenient().when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }


    /**
     * 실패 모드가 <b>사유별로</b> 솎인다 — 래치를 공유하면 먼저 찍은 쪽이 다른 사유의 첫 발생을 덮는다.
     *
     * <p>이 저장소가 같은 결함을 이미 두 번 이름 붙여 고쳤다. {@code EntryGate} — *"사유별로 따로
     * 센다 — 하나를 공유하면 먼저 찍은 쪽이 다른 사유의 첫 발생을 통째로 덮는다."*
     * {@code SendChatService} — *"카운터와 스로틀을 공유하면 … 영영 안 낫는 상태가 곧 복구될 장애의
     * 로그에 묻힌다."* 그리고 {@code ChatSessionRegistry} 자신이 형제 둘(입장 상한 · 수명 만료)에
     * 사유별 래치를 갖고 있다. 재등록 실패 · 시청자 수 갱신 실패 · emit 경합 드롭 셋만 공유로 남았다.
     *
     * <p>이 둘을 고른 이유는 <b>같은 회차에서 함께 나기 때문</b>이다 — 시청자 수 갱신이 재등록 바로
     * 뒤에 같은 타이머를 탄다. 즉 공유 래치에서는 10초 창 안의 두 사유가 아니라 <b>한 회차 안의</b>
     * 두 사유가 서로를 가린다. 상관관계가 그걸 더 나쁘게 만든다: 둘 다 Redis 가 흔들릴 때 함께 난다.
     */
    @Test
    @DisplayName("⭐ 한 회차에 두 사유가 나면 두 줄이 남는다 — 래치를 공유하면 뒤쪽이 통째로 묻힌다")
    void bothFailureModesSurviveTheSameRound() {
        // given: 한 회차에서 재등록과 시청자 수 갱신이 둘 다 실패한다
        given(sessionRepository.heartbeat(any(), any(), any()))
                .willReturn(Mono.error(new IllegalStateException("Redis down")));
        given(sessionRepository.count(any()))
                .willReturn(Mono.error(new IllegalStateException("Redis down")));
        register("s1", userA);

        // ⚠️ ListAppender 가 아니라 CopyOnWriteArrayList 로 받는다. 타이머 스레드가 로그를 쌓는 동안
        // 이 스레드가 순회하므로 ArrayList 면 ConcurrentModificationException 이 난다 — 실측으로 났고,
        // 그 빨강은 "래치가 공유다" 와 구분되지 않는다(하네스 사유의 빨강은 근거가 못 된다).
        List<String> logged = new CopyOnWriteArrayList<>();
        Logger logger = (Logger) LoggerFactory.getLogger(ChatSessionRegistry.class);
        AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                logged.add(event.getFormattedMessage());
            }
        };
        appender.start();
        logger.addAppender(appender);

        // when: 두 회차를 확인한다 — 한 회차의 재등록 뒤에 시청자 수 갱신이 따라오므로,
        // 두 번째 실패가 보이면 첫 회차는 그 갱신까지 끝났다.
        registry.startHeartbeat();
        try {
            await(() -> registry.heartbeatFailures() >= 2);
        } finally {
            registry.stopHeartbeat();
            logger.detachAppender(appender);
            appender.stop();
        }

        // then: 두 사유가 각자 한 줄을 남긴다
        assertThat(logged)
                .as("재등록 실패 줄이 없다 — 이 칸이 재는 두 사유 중 하나가 발생하지 않았다")
                .anySatisfy(line -> assertThat(line).startsWith("세션 명부 재등록 실패"));
        assertThat(logged)
                .as("시청자 수 갱신 실패 줄이 없다 — 래치를 공유하면 먼저 찍은 쪽이 이 줄을 덮는다")
                .anySatisfy(line -> assertThat(line).startsWith("시청자 수 갱신 실패"));
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
