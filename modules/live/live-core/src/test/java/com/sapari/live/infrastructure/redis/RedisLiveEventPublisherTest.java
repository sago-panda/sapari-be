package com.sapari.live.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mockito;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.sapari.live.application.port.LiveMetrics;
import com.sapari.live.application.port.RoomEndedSignal;

@ExtendWith(MockitoExtension.class)
class RedisLiveEventPublisherTest {

    private static final UUID ROOM_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final Instant ENDED_AT = Instant.ofEpochMilli(1_700_000_000_000L);

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> values;
    @Mock
    private LiveMetrics metrics;
    @InjectMocks
    private RedisLiveEventPublisher publisher;

    @Test
    @DisplayName("RoomEnded를 live:room:ended 채널에 {roomId, endedAt} JSON으로 발행한다(chat 계약)")
    void publishesRoomEndedAsJson() {
        given(redis.opsForValue()).willReturn(values);
        UUID roomId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        Instant endedAt = Instant.ofEpochMilli(1_700_000_000_000L);

        publisher.publishRoomEnded(roomId, endedAt);

        ArgumentCaptor<String> channel = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        then(redis).should().convertAndSend(channel.capture(), payload.capture());

        assertThat(channel.getValue()).isEqualTo("live:room:ended");
        assertThat(payload.getValue())
                .isEqualTo("{\"roomId\":\"11111111-2222-3333-4444-555555555555\",\"endedAt\":1700000000000}");
    }

    @Test
    @DisplayName("발행 실패는 삼키고 예외를 전파하지 않는다(at-most-once) — 대신 실패 지표를 남긴다")
    void swallowsPublishFailure() {
        given(redis.opsForValue()).willReturn(values);
        willThrow(new RuntimeException("redis down"))
                .given(redis).convertAndSend(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());

        assertThatCode(() -> publisher.publishRoomEnded(UUID.randomUUID(), Instant.ofEpochMilli(1L)))
                .doesNotThrowAnyException();
        then(metrics).should().roomEndedSignalFailed(RoomEndedSignal.EVENT);
    }

    /**
     * ⭐ <b>종료 키를 이벤트보다 먼저 남긴다(SPR-185).</b>
     *
     * <p>Pub/Sub 은 그 순간 구독하지 않던 쪽에 영영 오지 않는다. 키가 있으면 이벤트를 놓친 쪽도 종료를
     * 확인할 수 있다. 순서가 먼저인 이유는 이벤트를 받은 쪽이 곧바로 키를 읽어도 보이게 하려는 것이다.
     * TTL 70분의 하한은 chat 세션 최대 수명 60분이다 — 종료 전에 열린 세션이 그만큼 더 산다.
     */
    @Test
    @DisplayName("⭐ 종료 시 live:room:ended:{roomId} 를 종료 시각·70분 TTL 로 이벤트보다 먼저 남긴다")
    void writesTheEndedKeyBeforePublishing() {
        given(redis.opsForValue()).willReturn(values);

        publisher.publishRoomEnded(ROOM_ID, ENDED_AT);

        InOrder order = Mockito.inOrder(values, redis);
        order.verify(values).set("live:room:ended:" + ROOM_ID, "1700000000000", Duration.ofMinutes(70));
        order.verify(redis).convertAndSend(org.mockito.ArgumentMatchers.eq("live:room:ended"),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("⭐ 종료 키 기록이 실패해도 이벤트는 나가고, 실패 지표를 남긴다")
    void aFailedKeyWriteStillPublishes() {
        given(redis.opsForValue()).willReturn(values);
        willThrow(new RuntimeException("redis down")).given(values)
                .set(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(Duration.class));

        assertThatCode(() -> publisher.publishRoomEnded(ROOM_ID, ENDED_AT))
                .as("커밋 뒤라 던지면 되돌릴 것은 없고 판매자에게 종료 실패만 보인다")
                .doesNotThrowAnyException();
        then(redis).should().convertAndSend(org.mockito.ArgumentMatchers.eq("live:room:ended"),
                org.mockito.ArgumentMatchers.anyString());
        then(metrics).should().roomEndedSignalFailed(RoomEndedSignal.MARKER);
    }
}
