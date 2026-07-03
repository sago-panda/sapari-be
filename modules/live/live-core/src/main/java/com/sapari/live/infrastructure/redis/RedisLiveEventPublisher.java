
package com.sapari.live.infrastructure.redis;

import java.time.Instant;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.sapari.live.application.port.LiveEventPublisher;

/**
 * live 도메인 이벤트를 Redis Pub/Sub으로 발행하는 어댑터.
 *
 * <p>payload는 roomId(UUID)·endedAt(epochMillis long)뿐이라 수동 JSON으로 조립한다 — 두 값 모두
 * 이스케이프가 필요 없는 형식이라 인젝션 위험이 없고, 별도 직렬화 의존이 불필요하다.
 * 발행 실패는 삼키고 로그만 남긴다(at-most-once, chat 측 백스톱).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisLiveEventPublisher implements LiveEventPublisher {

    private final StringRedisTemplate redis;

    @Override
    public void publishRoomEnded(UUID roomId, Instant endedAt) {
        String payload = "{\"roomId\":\"" + roomId + "\",\"endedAt\":" + endedAt.toEpochMilli() + "}";
        try {
            redis.convertAndSend(LiveRedisKeys.roomEndedChannel(), payload);
            log.info("RoomEnded 발행. roomId={}", roomId);
        } catch (RuntimeException e) {
            // 발행 실패 시 chat 세션 정리가 누락될 수 있음 — 재조정(reconciliation)이 백스톱.
            log.error("RoomEnded 발행 실패 — chat 세션 정리 누락 가능. roomId={}", roomId, e);
        }
    }
}
