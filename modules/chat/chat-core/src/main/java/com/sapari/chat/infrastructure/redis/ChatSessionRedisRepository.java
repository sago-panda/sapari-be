package com.sapari.chat.infrastructure.redis;

import java.time.Duration;
import java.util.UUID;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Repository;

import com.sapari.chat.domain.repository.ChatSessionRepository;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * room:{roomId}:sessions HASH 어댑터 — 크로스 Pod 세션 집계.
 * count는 HLEN(탭 수)이 아니라 HVALS distinct(고유 유저 수)다 — 같은 유저 멀티탭은 1로 센다(§6.1 activeCount).
 */
@Repository
@RequiredArgsConstructor
public class ChatSessionRedisRepository implements ChatSessionRepository {

    private final ReactiveStringRedisTemplate redisTemplate;

    /**
     * 정상 회수는 세션별 HDEL과 방 종료 시 {@code clearRoom}이 담당한다. TTL은 그 둘이 다 실패했을 때의
     * 백스톱이다 — 방 종료 신호는 Pub/Sub(무영속)이라 그 순간 구독 중인 Pod가 없으면 통째로 유실되고,
     * 그러면 이 키는 지울 주체가 사라진다. 방송 최대 길이보다 넉넉히 잡아 정상 방송을 건드리지 않는다.
     */
    private static final Duration SESSIONS_TTL = Duration.ofHours(24);

    @Override
    public Mono<Void> add(UUID roomId, String sessionId, UUID userId) {
        String key = ChatRedisKeys.sessions(roomId);
        return redisTemplate.opsForHash()
                .put(key, sessionId, userId.toString())
                .then(redisTemplate.expire(key, SESSIONS_TTL))   // 입장마다 갱신 — 방송이 길어도 안전
                .then();
    }

    @Override
    public Mono<Void> remove(UUID roomId, String sessionId) {
        return redisTemplate.opsForHash()
                .remove(ChatRedisKeys.sessions(roomId), sessionId)
                .then();
    }

    @Override
    public Mono<Long> count(UUID roomId) {
        return redisTemplate.opsForHash()
                .values(ChatRedisKeys.sessions(roomId))
                .distinct()
                .count();
    }

    @Override
    public Mono<Void> clearRoom(UUID roomId) {
        return redisTemplate.delete(ChatRedisKeys.sessions(roomId)).then();
    }
}
