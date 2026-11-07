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
import com.sapari.streamingapp.websocket.EntryDeniedException;

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

    /** 이 파일이 집행시키는 (유저, 방) 상한 — 아래 50회 루프가 반드시 닿을 수 있는 작은 수. */
    private static final int CAP_REACHABLE_WITHIN_THE_LOOP = 4;

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
        // ⚠️ 상한을 넉넉히 주면 안 된다 — theReapedCounterFollowsTheRegistry 는 상한에 **닿아야**
        // 거둘 자리가 생긴다(reapUnresponsiveIfFull 은 자리가 찼을 때만 거둔다). 실측: 1,000 으로
        // 두면 50회 등록이 전부 통과해 거둔 수가 0 이 되고 그 칸이 깨진다.
        // 그래서 "닿을 수 있는 작은 수" 를 이 파일이 직접 정한다. 기본값을 베껴 적지는 않는다 —
        // 여기서 필요한 것은 기본값이 아니라 도달 가능한 상한이고, 베끼면 기본이 바뀔 때 조용히 갈린다.
        registry = new ChatSessionRegistry(sessionRepository, new ChatPermissionPolicy(), ChatMetrics.NOOP,
                CAP_REACHABLE_WITHIN_THE_LOOP);
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

    /**
     * 거부 카운터가 GC 뒤에도 <b>값을 따라오는지</b>.
     *
     * <p>게이지와 테스트를 나눈 이유는 실패 모양이 다르기 때문이다. 게이지는 대상을 잃으면
     * {@code NaN} 이라 눈에 띄는데, {@code CumulativeFunctionCounter} 는 {@code WeakReference<T>} 를
     * 들고 {@code count()} 가 {@code ref.get()==null} 이면 <b>마지막 값</b>을 돌려준다(javap:
     * {@code ifnull → getfield last; dreturn}). 즉 얼어붙어 "그 뒤로 거부가 없었다" 와 구분되지
     * 않는다 — 하필 집행을 세는 계열이다.
     *
     * <p>그래서 <b>0 을 단언해서는 못 잡는다.</b> 끊긴 카운터도 0 을 돌려준다. 거부를 먼저 만들어
     * 값을 1 로 올린 뒤 GC 를 밀고 그 1 이 보이는지를 봐야 한다 — 끊긴 쪽은 {@code last} 가 아직
     * 0 이라 그 자리에서 갈린다.
     *
     * <p>이 테스트는 레지스트리를 들 수밖에 없다(거부를 만들어야 한다). 그래서 "컨텍스트가 대상을
     * 들고 있다" 는 쪽은 {@link #theGaugeTargetOutlivesGarbageCollection} 이 따로 본다 — 끊기는
     * 것은 인라인 어댑터이고 그건 레지스트리를 들어도 수거된다.
     */
    @Test
    @DisplayName("⭐ 거부 카운터가 GC 뒤에도 값을 따라온다 — 끊기면 NaN 이 아니라 얼어붙는다")
    void theAdmissionCounterFollowsTheRegistryAfterGarbageCollection() {
        new ApplicationContextRunner()
                .withBean(ChatSessionRepository.class, ChatSessionGaugeConfigTest::stubRepository)
                .withBean(ChatPermissionPolicy.class)
                .withBean(ChatMetrics.class, () -> ChatMetrics.NOOP)
                .withBean(ChatSessionRegistry.class)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withUserConfiguration(ChatSessionGaugeConfig.class)
                .run(context -> {
                    ChatSessionRegistry contextRegistry = context.getBean(ChatSessionRegistry.class);
                    MeterRegistry contextMeters = context.getBean(MeterRegistry.class);

                    UUID room = UUID.randomUUID();
                    UUID user = UUID.randomUUID();
                    // 상한값을 모른 채 거부 1건을 만든다 — 이 테스트가 재는 것은 카운터이지 상한이
                    // 아니고, 상수를 쓰려면 그 상수를 패키지 밖으로 넓혀야 한다.
                    int attempts = 0;
                    while (contextRegistry.admissionCapped() == 0 && attempts < 50) {
                        try {
                            contextRegistry.register("c" + attempts, new ChatSession(
                                    room, user, ChatRole.BUYER, "닉", "e@example.com", false))
                                    .block(VERIFY_CEILING);
                        } catch (EntryDeniedException expected) {
                            // 상한에 걸렸다 — 이 테스트가 필요한 상태다
                        }
                        attempts++;
                    }
                    assertThat(contextRegistry.admissionCapped())
                            .as("거부가 한 건도 생기지 않았다 — 셀 것이 없으면 이 테스트는 무의미하다")
                            .isEqualTo(1);

                    forceWeakReferenceClearing();

                    assertThat(contextMeters.get(ChatSessionGaugeConfig.ADMISSION_CAPPED_COUNTER)
                            .functionCounter().count())
                            .as("카운터가 대상을 잃고 얼어붙었다 — 얼어붙은 값은 '거부 없음'과 구분되지 않는다")
                            .isEqualTo(1);
                });
    }

    private static ChatSessionRepository stubRepository() {
        ChatSessionRepository repository = mock(ChatSessionRepository.class);
        lenient().when(repository.add(any(), any(), any())).thenReturn(Mono.empty());
        lenient().when(repository.remove(any(), any())).thenReturn(Mono.empty());
        lenient().when(repository.heartbeatInterval()).thenReturn(Duration.ofMinutes(10));
        return repository;
    }

    /**
     * 거둔 자리 카운터가 <b>값을 따라오는지</b>.
     *
     * <p>집합 단언({@link #theRegisteredMeterSetIsPinned})은 "안 만든 미터" 를 잡고 <b>엉뚱하게
     * 배선된 미터" 는 못 잡는다 — 접근자를 형제 것으로 바꿔도 이름은 그대로라 집합이 같다. 실측:
     * 그렇게 바꾸면 두 클래스 15건이 전부 초록이었다.
     *
     * <p>하필 이 둘은 당직자가 <b>짝으로</b> 읽도록 저장소 문서가 지시하는 쌍이다. 짝의 한쪽이
     * 형제 값을 보고하면 "거부는 오르는데 수거는 평평" 이라는 공격 서명이 그대로 만들어진다.
     */
    @Test
    @DisplayName("⭐ 거둔 자리 카운터가 값을 따라온다 — 엉뚱하게 배선돼도 집합 단언은 초록이다")
    void theReapedCounterFollowsTheRegistry() {
        UUID room = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        int admitted = 0;
        for (int i = 0; i < 50; i++) {
            try {
                registry.register("r" + i, new ChatSession(
                        room, user, ChatRole.BUYER, "닉", "e@example.com", false)).block(VERIFY_CEILING);
                admitted++;
            } catch (EntryDeniedException denied) {
                break;   // 상한에 닿았다 — 이제 전부 죽은 것으로 만든다
            }
        }
        assertThat(admitted).as("상한에 닿지 않아 거둘 자리를 만들 수 없다").isPositive();
        // 기한 ZERO 를 넘긴다 — isUnresponsive 가 "now - last >= 기한" 이라 0 이면 전부 참이다.
        // 생존 시계를 미는 진입점(expireLiveness)은 websocket 패키지 전용이고, 그걸 이 테스트
        // 때문에 넓히지 않는다. 여기서 재는 것은 수거의 판정이 아니라 카운터의 배선이다.
        int reaped = registry.reapUnresponsiveIfFull(user, room, Duration.ZERO);
        assertThat(reaped).isEqualTo(admitted);

        assertThat(meters.get(ChatSessionGaugeConfig.SLOTS_REAPED_COUNTER).functionCounter().count())
                .as("거둔 수가 아닌 값을 보고한다 — 짝으로 읽는 당직 판독이 통째로 틀린다")
                .isEqualTo(reaped);
        assertThat(meters.get(ChatSessionGaugeConfig.ADMISSION_CAPPED_COUNTER).functionCounter().count())
                .as("거부 카운터가 거둔 수에 끌려갔다").isEqualTo(1);
    }

    @Test
    @DisplayName("⭐ 등록한 미터 집합을 고정한다 — 이름으로 찾는 단언은 '안 만든 미터' 를 못 잡는다")
    void theRegisteredMeterSetIsPinned() {
        // 이 클래스의 다른 테스트는 전부 미터를 <b>이름으로</b> 조회한다. 그러면 새 미터를 상수만
        // 만들고 등록을 빠뜨려도 아무것도 깨지지 않는다 — 실제로 그랬다: chat.sessions.reaped 가
        // 접근자와 문서에는 있는데 등록이 없었고, 문서 두 곳(infra/AGENTS.md ·
        // modules/chat/AGENTS.md)이 당직자에게 그 지표를 보라고 적고 있었다. 빈 패널이 하필 "공격" 서명으로 읽히는 쪽이라 더 나쁘다.
        assertThat(meters.getMeters().stream().map(meter -> meter.getId().getName()).toList())
                .as("등록된 미터 집합이 바뀌었다 — 늘었으면 문서에, 줄었으면 당직 판독에 영향이 간다")
                .containsExactlyInAnyOrder(
                        ChatSessionGaugeConfig.SESSIONS_GAUGE,
                        ChatSessionGaugeConfig.ROOMS_GAUGE,
                        ChatSessionGaugeConfig.ADMISSION_CAPPED_COUNTER,
                        ChatSessionGaugeConfig.SLOTS_REAPED_COUNTER);
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
