package com.sapari.chat.infrastructure.redis;

import java.time.Duration;
import java.util.UUID;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Repository;

import com.sapari.chat.domain.exception.KickStoreCorruptedException;
import com.sapari.chat.domain.repository.ChatKickRepository;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * chat:kicked:{roomId} SET <b>읽기</b> 어댑터 — 멤버십 조회(SISMEMBER)와 방 종료 시 만료 부여(EXPIRE).
 * 리액티브라 소비처가 streaming-app이다. 등록은 블로킹인 {@link ChatKickWriteRedisRepository}가 맡고,
 * 같은 패키지에 두어 {@link ChatRedisKeys}와 {@link RedisWrongType}을 함께 쓴다 — 읽는 쪽과 쓰는 쪽이
 * 다른 키를 보거나 같은 실패를 다르게 분류하면 그 어긋남은 조용하다.
 */
@Repository
@RequiredArgsConstructor
public class ChatKickRedisRepository implements ChatKickRepository {

    private final ReactiveStringRedisTemplate redisTemplate;

    @Override
    public Mono<Boolean> isKicked(UUID roomId, UUID userId) {
        String key = ChatRedisKeys.kicked(roomId);
        return redisTemplate.opsForSet()
                .isMember(key, userId.toString())
                // 일시 장애는 그대로 흘려보낸다 — 소비처의 fail-open 정책은 바뀌지 않는다.
                // 낫지 않는 쪽만 타입으로 갈라, 소비처가 "곧 복구될 실패"와 섞어 로그하지 않게 한다.
                .onErrorMap(RedisWrongType::matches, e -> new KickStoreCorruptedException(key, e));
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
