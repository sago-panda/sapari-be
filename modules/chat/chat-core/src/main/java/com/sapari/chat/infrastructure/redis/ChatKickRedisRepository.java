package com.sapari.chat.infrastructure.redis;

import java.util.UUID;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Repository;

import com.sapari.chat.domain.repository.ChatKickRepository;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * kicked:{roomId} SET 어댑터 — 멤버십 조회(SISMEMBER)와 방 종료 시 정리(DEL).
 * 강퇴 등록(SADD)은 api-app(KickUserService) 책임이라 여기 두지 않는다.
 */
@Repository
@RequiredArgsConstructor
public class ChatKickRedisRepository implements ChatKickRepository {

    private final ReactiveStringRedisTemplate redisTemplate;

    @Override
    public Mono<Boolean> isKicked(UUID roomId, UUID userId) {
        return redisTemplate.opsForSet()
                .isMember(ChatRedisKeys.kicked(roomId), userId.toString());
    }

    @Override
    public Mono<Void> clearRoom(UUID roomId) {
        return redisTemplate.delete(ChatRedisKeys.kicked(roomId)).then();
    }
}
