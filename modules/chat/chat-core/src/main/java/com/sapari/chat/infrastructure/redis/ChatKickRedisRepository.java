package com.sapari.chat.infrastructure.redis;

import java.time.Duration;
import java.util.UUID;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Repository;

import com.sapari.chat.domain.repository.ChatKickRepository;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * kicked:{roomId} SET 어댑터 — 멤버십 조회(SISMEMBER)와 방 종료 시 만료 부여(EXPIRE).
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

    /**
     * 종료된 방의 강퇴 명단이 살아 있는 시간. 잘못된 종료 신호를 알아채고 되돌릴 수 있는 창이기도 해서,
     * 사람이 개입할 여유는 되되 끝난 방의 키를 오래 붙들지는 않는 길이로 잡는다.
     */
    private static final Duration KICKED_RETENTION = Duration.ofHours(24);

    @Override
    public Mono<Void> expireAfterRoomEnded(UUID roomId) {
        return redisTemplate.expire(ChatRedisKeys.kicked(roomId), KICKED_RETENTION).then();
    }
}
