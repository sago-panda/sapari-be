package com.sapari.streamingapp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.sapari.chat.application.port.ChatMetrics;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.model.ChatSession;
import com.sapari.chat.domain.repository.ChatSessionRepository;
import com.sapari.chat.domain.rule.ChatPermissionPolicy;
import com.sapari.streamingapp.websocket.ChatSessionRegistry;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import reactor.core.publisher.Mono;

/**
 * 세션·방 게이지가 <b>실제 값</b>을 낸다.
 *
 * <p>등록만 재면 모자란다 — 등록은 됐는데 늘 0 을 돌려주는 게이지는 "연결이 하나도 없다" 와
 * 구분되지 않고, 그 값으로 연결 수 상한을 정하면 숫자가 통째로 틀린다. 그래서 세션을 넣고
 * <b>빼면서</b> 게이지가 따라오는지 본다.
 */
@DisplayName("세션 게이지 — 등록만이 아니라 값이 따라온다")
class ChatSessionGaugeConfigTest {

    private static final Duration VERIFY_CEILING = Duration.ofSeconds(10);

    private final ChatSessionGaugeConfig config = new ChatSessionGaugeConfig();
    private MeterRegistry meters;
    private ChatSessionRegistry registry;

    private final UUID roomA = UUID.randomUUID();
    private final UUID roomB = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        ChatSessionRepository sessionRepository = mock(ChatSessionRepository.class);
        lenient().when(sessionRepository.add(any(), any(), any())).thenReturn(Mono.empty());
        lenient().when(sessionRepository.remove(any(), any())).thenReturn(Mono.empty());
        lenient().when(sessionRepository.heartbeatInterval()).thenReturn(Duration.ofMinutes(10));
        registry = new ChatSessionRegistry(sessionRepository, new ChatPermissionPolicy(), ChatMetrics.NOOP);
        config.chatSessionGauges(registry, providing(meters));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MeterRegistry> providing(MeterRegistry value) {
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        lenient().when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private void register(String sid, UUID roomId) {
        registry.register(sid, new ChatSession(roomId, UUID.randomUUID(), ChatRole.BUYER,
                "닉", "e@example.com", false)).block(VERIFY_CEILING);
    }

    private double gauge(String name) {
        return meters.get(name).gauge().value();
    }

    @Test
    @DisplayName("⭐ 세션을 넣고 빼면 게이지가 따라온다 — 등록만 되고 0 이면 상한을 고를 수 없다")
    void theSessionGaugeFollowsTheRegistry() {
        assertThat(gauge(ChatSessionGaugeConfig.SESSIONS_GAUGE)).isZero();

        register("s1", roomA);
        register("s2", roomA);
        register("s3", roomB);
        assertThat(gauge(ChatSessionGaugeConfig.SESSIONS_GAUGE)).isEqualTo(3);

        registry.unregister(roomA, "s1").block(VERIFY_CEILING);
        assertThat(gauge(ChatSessionGaugeConfig.SESSIONS_GAUGE)).isEqualTo(2);
    }

    @Test
    @DisplayName("⭐ 방 수를 따로 낸다 — 2,000 세션이 방 하나인지 방 200개인지로 팬아웃 비용이 갈린다")
    void theRoomGaugeIsSeparate() {
        register("s1", roomA);
        register("s2", roomA);
        assertThat(gauge(ChatSessionGaugeConfig.ROOMS_GAUGE))
                .as("같은 방 두 세션이 방 수를 2 로 만들면 '방당 세션' 이 틀린다").isEqualTo(1);

        register("s3", roomB);
        assertThat(gauge(ChatSessionGaugeConfig.ROOMS_GAUGE)).isEqualTo(2);

        // 방이 비면 항목째 사라진다 — 죽은 방이 쌓이면 게이지가 영원히 올라간다
        registry.unregister(roomB, "s3").block(VERIFY_CEILING);
        assertThat(gauge(ChatSessionGaugeConfig.ROOMS_GAUGE)).isEqualTo(1);
    }

    /**
     * GC 가 weak 참조를 실제로 정리하는 지점까지 밀어 둔다 — 정리되지 않았으면 아래 단언은 아무것도
     * 보장하지 않으므로, 조용히 통과시키지 않고 그 사실로 실패한다.
     */
    private static void forceWeakReferenceClearing() {
        Object sentinel = new Object();
        WeakReference<Object> probe = new WeakReference<>(sentinel);
        sentinel = null;
        for (int attempt = 0; attempt < 50 && probe.get() != null; attempt++) {
            System.gc();
        }
        assertThat(probe.get())
                .as("GC 가 weak 참조를 정리하지 않았다 — 이 테스트는 아무것도 보장하지 못한다")
                .isNull();
    }

    @Test
    @DisplayName("⭐ GC 뒤에도 값이 남는다 — 게이지 대상을 빈 아닌 것으로 바꾸면 조용히 NaN 이 된다")
    void theGaugeTargetOutlivesGarbageCollection() {
        // Micrometer 게이지는 대상을 weak 로 들고, 끊기면 값이 NaN 이 된다. 운영에서 안전한 이유는
        // 넘기는 것이 싱글톤 빈이라 컨텍스트가 강하게 들고 있기 때문인데, 그 "이유" 가 지금까지
        // 테스트로 고정돼 있지 않았다 — 위 테스트들은 레지스트리를 필드로 들고 있어서 어댑터나
        // 임시 객체로 바뀌어도 같이 살아남는다. 그래서 여기서는 컨텍스트에만 들리게 두고 GC 를 민다.
        new ApplicationContextRunner()
                .withBean(ChatSessionRepository.class, ChatSessionGaugeConfigTest::stubRepository)
                .withBean(ChatPermissionPolicy.class)
                .withBean(ChatMetrics.class, () -> ChatMetrics.NOOP)
                .withBean(ChatSessionRegistry.class)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withUserConfiguration(ChatSessionGaugeConfig.class)
                .run(context -> {
                    // 의도적으로 ChatSessionRegistry 를 여기서 들지 않는다 — 들면 이 테스트가
                    // 막으려는 바로 그 강참조를 테스트가 다시 만든다.
                    MeterRegistry contextMeters = context.getBean(MeterRegistry.class);
                    forceWeakReferenceClearing();

                    assertThat(contextMeters.get(ChatSessionGaugeConfig.SESSIONS_GAUGE).gauge().value())
                            .as("게이지가 NaN 이다 — 대상이 컨텍스트에 들리지 않는 객체로 바뀌었다")
                            .isNotNaN();
                    assertThat(contextMeters.get(ChatSessionGaugeConfig.ROOMS_GAUGE).gauge().value())
                            .isNotNaN();
                });
    }

    private static ChatSessionRepository stubRepository() {
        ChatSessionRepository repository = mock(ChatSessionRepository.class);
        lenient().when(repository.add(any(), any(), any())).thenReturn(Mono.empty());
        lenient().when(repository.remove(any(), any())).thenReturn(Mono.empty());
        lenient().when(repository.heartbeatInterval()).thenReturn(Duration.ofMinutes(10));
        return repository;
    }

    @Test
    @DisplayName("MeterRegistry 가 없으면 등록을 건너뛴다 — registry 없는 컨텍스트가 이 설정 때문에 깨지지 않게")
    void withoutAMeterRegistryNothingIsRegistered() {
        assertThatCode(() -> {
            var marker = config.chatSessionGauges(registry, providing(null));
            assertThat(marker.registered()).isFalse();
        }).doesNotThrowAnyException();
    }
}
