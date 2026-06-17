package com.sapari.chat.infrastructure.redis;

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

    @Override
    public Mono<Void> add(UUID roomId, String sessionId, UUID userId) {
        return redisTemplate.opsForHash()
                .put(ChatRedisKeys.sessions(roomId), sessionId, userId.toString())
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
