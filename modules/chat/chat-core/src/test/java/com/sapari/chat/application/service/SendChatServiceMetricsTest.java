package com.sapari.chat.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.lenient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sapari.chat.application.port.ChatBroadcaster;
import com.sapari.chat.application.port.ChatMetrics;
import com.sapari.chat.application.port.RateLimitResult;
import com.sapari.chat.application.port.RateLimiter;
import com.sapari.chat.command.SendChatCommand;
import com.sapari.chat.domain.exception.KickStoreCorruptedException;
import com.sapari.chat.domain.model.ChatMessage;
import com.sapari.chat.domain.repository.ChatBanRepository;
import com.sapari.chat.domain.repository.ChatKickRepository;
import com.sapari.chat.domain.repository.ChatMessageRepository;
import com.sapari.chat.domain.rule.ChatPermissionPolicy;
import com.sapari.chat.domain.rule.ProfanityFilter;
import com.sapari.chat.infrastructure.metrics.MicrometerChatMetrics;
import com.sapari.global.time.TimeProvider;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * 전송 경로의 <b>가용성 정책이 발화한 사실이 밖으로 나가는지</b>를 본다.
 *
 * <p>이 저장소의 chat 은 실패를 삼키지 않는 대신 방향을 정해 삼킨다 — 모더레이션 조회는 열고,
 * 실시간 전달은 포기한다. 그 선택은 전부 "지금은 집행되지 않는다" 를 뜻하는데, 지금까지 그 사실은
 * 프로세스 안의 {@code AtomicLong} 과 10초마다 솎아낸 로그 줄에만 있었다. 로그는 사람이 읽을 때만
 * 읽히고 솎아내진 쪽은 아예 안 남으므로, <b>정책이 쉬고 있는 구간을 알림으로 받을 수가 없었다.</b>
 *
 * <p>그래서 단언을 "로그가 찍힌다" 로 두지 않는다 — 그건 이미 다른 테스트가 본다. 실제 레지스트리의
 * 카운터가 오르는지를 본다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("전송 경로 관측 — 정책이 쉬면 지표가 오른다")
class SendChatServiceMetricsTest {

    /** 반응형 종결자(.block() / StepVerifier) 상한 — 없으면 회귀가 테스트를 실패시키는 대신 <b>멈추게</b> 한다(근거: modules/chat/AGENTS.md). */
    private static final Duration VERIFY_CEILING = Duration.ofSeconds(10);

    @Mock
    private ChatKickRepository kickRepository;

    @Mock
    private ChatBanRepository banRepository;

    @Mock
    private RateLimiter rateLimiter;

    @Mock
    private ChatMessageRepository chatMessageRepository;

    @Mock
    private ChatBroadcaster broadcaster;

    private MeterRegistry registry;
    private SendChatService service;

    private final UUID roomId = UUID.randomUUID();
    private final UUID senderId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        service = newService(new MicrometerChatMetrics(registry));
        lenient().when(banRepository.isBanned(any())).thenReturn(Mono.just(false));
    }

    private SendChatService newService(ChatMetrics metrics) {
        return new SendChatService(
                new ChatPermissionPolicy(),
                new ProfanityFilter(Set.of("욕설"), Set.of()),
                kickRepository, banRepository, rateLimiter, chatMessageRepository, broadcaster,
                new TimeProvider(Clock.fixed(Instant.parse("2026-06-18T00:00:00Z"), ZoneOffset.UTC)),
                metrics);
    }

    private SendChatCommand normalSend() {
        return new SendChatCommand(roomId, senderId, "BUYER", false, true,
                "닉네임", "buyer@example.com", "NORMAL", "안녕하세요", "client-1");
    }

    private void stubSaveAndPublish() {
        willAnswer(inv -> Mono.just(((ChatMessage) inv.getArgument(0)).toBuilder().id("genId").build()))
                .given(chatMessageRepository).save(any());
        given(rateLimiter.tryAcquire(any())).willReturn(Mono.just(new RateLimitResult(true, 0)));
    }

    private double count(String name, String... tags) {
        Counter counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    @DisplayName("⭐ 강퇴 조회가 실패해 통과시키면 policy=kick checkpoint=send failure=unavailable 이 오른다")
    void kick_read_failure_is_reported() {
        // given
        stubSaveAndPublish();
        given(broadcaster.publish(any(), any())).willReturn(Mono.empty());
        given(kickRepository.isKicked(any(), any()))
                .willReturn(Mono.error(new IllegalStateException("Redis down")));

        // when: fail-open 이라 전송은 성공한다
        StepVerifier.create(service.send(normalSend())).expectNextCount(1).expectComplete().verify(VERIFY_CEILING);

        // then
        assertThat(count("chat.policy.open", "policy", "kick", "checkpoint", "send",
                "failure", "unavailable")).isEqualTo(1);
    }

    @Test
    @DisplayName("⭐ 키 타입 충돌은 failure=store_corrupted 로 갈린다 — 재시도로 낫지 않아 대응이 정반대다")
    void corrupted_store_is_a_separate_series() {
        // given
        stubSaveAndPublish();
        given(broadcaster.publish(any(), any())).willReturn(Mono.empty());
        given(kickRepository.isKicked(any(), any()))
                .willReturn(Mono.error(new KickStoreCorruptedException("chat:kicked:" + roomId, null)));

        // when
        StepVerifier.create(service.send(normalSend())).expectNextCount(1).expectComplete().verify(VERIFY_CEILING);

        // then: 일시 장애 쪽은 0 이어야 한다 — 한 통에 담기면 영영 안 낫는 상태가 묻힌다
        assertThat(count("chat.policy.open", "policy", "kick", "checkpoint", "send",
                "failure", "store_corrupted")).isEqualTo(1);
        assertThat(count("chat.policy.open", "policy", "kick", "checkpoint", "send",
                "failure", "unavailable")).isZero();
    }

    @Test
    @DisplayName("⭐ 밴 조회가 실패해 통과시키면 policy=ban 이 오른다")
    void ban_read_failure_is_reported() {
        // given
        stubSaveAndPublish();
        given(broadcaster.publish(any(), any())).willReturn(Mono.empty());
        given(kickRepository.isKicked(any(), any())).willReturn(Mono.just(false));
        given(banRepository.isBanned(any())).willReturn(Mono.error(new IllegalStateException("Redis down")));

        // when
        StepVerifier.create(service.send(normalSend())).expectNextCount(1).expectComplete().verify(VERIFY_CEILING);

        // then
        assertThat(count("chat.policy.open", "policy", "ban", "checkpoint", "send",
                "failure", "unavailable")).isEqualTo(1);
    }

    @Test
    @DisplayName("⭐ 저장 뒤 발행이 실패하면 stage=redis_publish 가 오른다 — 영속은 됐고 실시간만 잃었다")
    void publish_failure_is_reported() {
        // given
        stubSaveAndPublish();
        given(kickRepository.isKicked(any(), any())).willReturn(Mono.just(false));
        given(broadcaster.publish(any(), any()))
                .willReturn(Mono.error(new IllegalStateException("Redis down")));

        // when: 저장은 됐으므로 발신자에게는 ack 가 간다
        StepVerifier.create(service.send(normalSend())).expectNextCount(1).expectComplete().verify(VERIFY_CEILING);

        // then
        assertThat(count("chat.delivery.dropped", "stage", "redis_publish")).isEqualTo(1);
    }

    @Test
    @DisplayName("⭐ 정상 전송에서는 어떤 열화 지표도 오르지 않는다 — 평상시 0 이어야 알림을 걸 수 있다")
    void healthy_send_reports_nothing() {
        // given
        stubSaveAndPublish();
        given(kickRepository.isKicked(any(), any())).willReturn(Mono.just(false));
        given(broadcaster.publish(any(), any())).willReturn(Mono.empty());

        // when
        StepVerifier.create(service.send(normalSend())).expectNextCount(1).expectComplete().verify(VERIFY_CEILING);

        // then
        assertThat(registry.find("chat.policy.open").counters()).isEmpty();
        assertThat(registry.find("chat.delivery.dropped").counters()).isEmpty();
    }

    @Test
    @DisplayName("⭐ 지표 기록이 던져도 fail-open 은 뒤집히지 않는다 — 관측이 가용성 정책을 바꾸면 안 된다")
    void a_throwing_registry_must_not_flip_fail_open() {
        // given: 모든 계측 호출이 던지는 레지스트리. 호출부가 onErrorResume 람다 안이라, 여기서
        // 예외가 새면 Reactor 가 하류로 전파해 "통과시키기로 한" 전송이 거부된다.
        SendChatService throwingMetrics = newService(new MicrometerChatMetrics(new SimpleMeterRegistry() {
            @Override
            public Counter counter(String name, String... tags) {
                throw new IllegalStateException("registry 고장");
            }
        }));
        stubSaveAndPublish();
        given(broadcaster.publish(any(), any())).willReturn(Mono.empty());
        given(kickRepository.isKicked(any(), any()))
                .willReturn(Mono.error(new IllegalStateException("Redis down")));

        // when & then: 전송이 성공해야 한다
        StepVerifier.create(throwingMetrics.send(normalSend()))
                .expectNextCount(1)
                .expectComplete()
                .verify(VERIFY_CEILING);
    }
}
