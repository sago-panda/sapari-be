package com.sapari.chat.infrastructure.redis;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import reactor.core.publisher.Flux;
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
        String payload = "{\"roomId\":\"" + roomId + "\",\"endedAt\":\"2026-07-03T00:00:00Z\"}";

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
        StepVerifier.create(source.parse("{\"endedAt\":\"2026-07-03T00:00:00Z\"}")).verifyComplete();
    }
}
