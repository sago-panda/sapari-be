package com.sapari.live.infrastructure.redis;

import java.time.Instant;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.sapari.live.application.port.LiveEventPublisher;
import com.sapari.live.application.port.LiveMetrics;
import com.sapari.live.application.port.RoomEndedSignal;

/**
 * live 도메인 이벤트를 Redis Pub/Sub으로 발행하는 어댑터.
 *
 * <p>payload는 roomId(UUID)·endedAt(epochMillis long)뿐이라 수동 JSON으로 조립한다 — 두 값 모두
 * 이스케이프가 필요 없는 형식이라 인젝션 위험이 없고, 별도 직렬화 의존이 불필요하다.
 *
 * <p><b>종료 키를 먼저 남기고 발행한다</b>({@link LiveRedisKeys#roomEnded}, SPR-185). Pub/Sub 은 그 순간
 * 구독하지 않던 쪽에 영영 오지 않으므로, 이벤트를 놓친 쪽이 키로 종료를 확인하게 하려는 것이다 —
 * ⚠️ 키를 읽는 쪽은 아직 없다(chat 후속 작업). 그 전까지는 이벤트 실패가 곧 종료 신호 유실이다.
 *
 * <p><b>두 신호는 따로 시도하고, 실패는 던지지 않는다.</b> 호출자는 종료 커밋 뒤의 {@code afterCommit}
 * 이라 던져도 되돌릴 것이 없고 판매자에게 종료 실패만 보인다. 한쪽 실패가 다른 쪽을 막지 않게 하는
 * 것은 키를 읽는 쪽이 생기면 둘이 서로의 백스톱이 되기 때문이다. 실패는 ERROR 로그와 {@link LiveMetrics#roomEndedSignalFailed}
 * 로 남긴다 — 재시도·아웃박스는 없다(at-most-once).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisLiveEventPublisher implements LiveEventPublisher {

    private final StringRedisTemplate redis;
    private final LiveMetrics metrics;

    @Override
    public void publishRoomEnded(UUID roomId, Instant endedAt) {
        try {
            redis.opsForValue().set(LiveRedisKeys.roomEnded(roomId),
                    String.valueOf(endedAt.toEpochMilli()), LiveRedisKeys.ROOM_ENDED_TTL);
        } catch (RuntimeException e) {
            // 이벤트는 그대로 보낸다 — 지금 구독 중인 쪽은 그것으로 안다. 놓친 쪽만 모르게 된다.
            metrics.roomEndedSignalFailed(RoomEndedSignal.MARKER);
            log.error("RoomEnded 종료 키 기록 실패 — 이벤트를 놓친 쪽은 종료를 모를 수 있다. roomId={}",
                    roomId, e);
        }
        String payload = "{\"roomId\":\"" + roomId + "\",\"endedAt\":" + endedAt.toEpochMilli() + "}";
        try {
            redis.convertAndSend(LiveRedisKeys.roomEndedChannel(), payload);
            log.info("RoomEnded 발행. roomId={}", roomId);
        } catch (RuntimeException e) {
            // 발행 실패 시 chat 세션 정리가 누락될 수 있음 — 재조정(reconciliation)이 백스톱.
            metrics.roomEndedSignalFailed(RoomEndedSignal.EVENT);
            log.error("RoomEnded 발행 실패 — chat 세션 정리 누락 가능. roomId={}", roomId, e);
        }
    }
}
