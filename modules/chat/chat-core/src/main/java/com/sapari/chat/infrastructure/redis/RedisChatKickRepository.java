package com.sapari.chat.infrastructure.redis;

import java.util.UUID;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Repository;

import com.sapari.chat.domain.repository.ChatKickRepository;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * kicked:{roomId} SET 멤버십 조회(SISMEMBER) — 읽기 전용.
 * 강퇴 등록(SADD)·방 정리(DEL)는 api-app(KickUserService·EndLive 리스너) 책임이라 여기 두지 않는다.
 */
@Repository
@RequiredArgsConstructor
public class RedisChatKickRepository implements ChatKickRepository {

    private final ReactiveStringRedisTemplate redisTemplate;

    @Override
    public Mono<Boolean> isKicked(UUID roomId, UUID userId) {
        return redisTemplate.opsForSet()
                .isMember(ChatRedisKeys.kicked(roomId), userId.toString());
    }
}
