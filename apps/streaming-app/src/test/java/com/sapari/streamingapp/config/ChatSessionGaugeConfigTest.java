package com.sapari.streamingapp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

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

    @Test
    @DisplayName("MeterRegistry 가 없으면 등록을 건너뛴다 — registry 없는 컨텍스트가 이 설정 때문에 깨지지 않게")
    void withoutAMeterRegistryNothingIsRegistered() {
        assertThatCode(() -> {
            var marker = config.chatSessionGauges(registry, providing(null));
            assertThat(marker.registered()).isFalse();
        }).doesNotThrowAnyException();
    }
}
