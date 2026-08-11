package com.sapari.live.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

@ExtendWith(MockitoExtension.class)
class RedisLiveEventPublisherTest {

    @Mock
    private StringRedisTemplate redis;
    @InjectMocks
    private RedisLiveEventPublisher publisher;

    @Test
    @DisplayName("RoomEnded를 live:room:ended 채널에 {roomId, endedAt} JSON으로 발행한다(chat 계약)")
    void publishesRoomEndedAsJson() {
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
    @DisplayName("발행 실패는 삼키고 예외를 전파하지 않는다(at-most-once)")
    void swallowsPublishFailure() {
        willThrow(new RuntimeException("redis down"))
                .given(redis).convertAndSend(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());

        assertThatCode(() -> publisher.publishRoomEnded(UUID.randomUUID(), Instant.ofEpochMilli(1L)))
                .doesNotThrowAnyException();
    }
}
