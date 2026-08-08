package com.sapari.chat.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.ReactiveSubscription.Message;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

class RedisLiveRoomEndedSourceTest {

    private RedisLiveRoomEndedSource source;

    @BeforeEach
    void setUp() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        // 생성자가 채널 구독을 즉시 연결(autoConnect 0)하므로 stub을 먼저 건다.
        doReturn(Flux.empty()).when(redis).listenToChannel("live:room:ended");
        source = new RedisLiveRoomEndedSource(redis);
    }

    @Test
    @DisplayName("parse — payload에서 roomId 추출")
    void parse_extracts_roomId() {
        // given
        UUID roomId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        // live가 실제로 싣는 모양 그대로 쓴다 — endedAt은 ISO 문자열이 아니라 epoch millis(숫자)다.
        // 픽스처가 실제와 다르면 나중에 endedAt을 읽게 됐을 때 이 테스트가 거짓 안심을 준다.
        String payload = "{\"roomId\":\"" + roomId + "\",\"endedAt\":1783036800000}";

        // when & then
        StepVerifier.create(source.parse(payload)).expectNext(roomId).verifyComplete();
    }

    @Test
    @DisplayName("parse — 깨진 payload는 skip(빈 Mono)")
    void parse_skips_broken() {
        // when & then
        StepVerifier.create(source.parse("{깨진 json")).verifyComplete();
    }

    @Test
    @DisplayName("parse — roomId 필드 없으면 skip")
    void parse_skips_missing_roomId() {
        // when & then
        StepVerifier.create(source.parse("{\"endedAt\":1783036800000}")).verifyComplete();
    }

    @Test
    @DisplayName("채널 구독이 끊겨도 재구독으로 되살아나 이후 종료 신호가 다시 들어온다")
    void ended_recovers_after_upstream_failure() {
        // given: 첫 구독은 즉시 끊기고, 재구독부터 정상 스트림을 준다.
        // 이 스트림이 죽은 채로 남으면 방이 끝나도 세션이 안 닫힌다 — 자가복구가 유일한 방어다.
        UUID roomId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        Sinks.Many<Message<String, String>> afterReconnect = Sinks.many().replay().all();
        AtomicInteger subscribeCount = new AtomicInteger();
        ReactiveStringRedisTemplate flaky = mock(ReactiveStringRedisTemplate.class);
        doReturn(Flux.defer(() -> subscribeCount.getAndIncrement() == 0
                ? Flux.<Message<String, String>>error(new RuntimeException("연결 끊김"))
                : afterReconnect.asFlux()))
                .when(flaky).listenToChannel("live:room:ended");

        RedisLiveRoomEndedSource recovering = new RedisLiveRoomEndedSource(flaky);
        Message<String, String> ended = mock(Message.class);
        doReturn("{\"roomId\":\"" + roomId + "\"}").when(ended).getMessage();
        afterReconnect.tryEmitNext(ended);

        // when & then
        StepVerifier.create(recovering.ended())
                .expectNext(roomId)
                .thenCancel()
                .verify(Duration.ofSeconds(10));

        assertThat(subscribeCount.get()).isGreaterThan(1);   // 실제로 다시 붙었다
    }
}
