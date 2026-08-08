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
 * chat:kicked:{roomId} SET 어댑터 — 멤버십 조회(SISMEMBER)와 방 종료 시 만료 부여(EXPIRE).
 * 강퇴 등록(SADD)은 api-app(KickUserService) 책임이라 여기 두지 않는다.
 */
@Repository
@RequiredArgsConstructor
public class ChatKickRedisRepository implements ChatKickRepository {

    /**
     * 키가 SET이 아닐 때 Redis가 돌려주는 에러 응답의 첫 낱말.
     *
     * <p><b>예외 타입으로는 갈라낼 수 없어 메시지로 판별한다.</b> 실측하면 SISMEMBER는
     * {@code RedisSystemException("Error in execution")}으로 감싸여 오고, 그 원인이
     * {@code RedisCommandExecutionException}이다 — 그런데 이 타입은 서버가 에러로 답한 모든 경우
     * (OOM·READONLY 등)에 똑같이 쓰이므로 타입만 봐서는 일시 장애와 구분되지 않는다. 구분해 주는 건
     * 서버가 그대로 실어 보낸 메시지의 첫 낱말뿐이라, 여기에 타입 검사를 더해도 좁혀지는 것 없이
     * 드라이버 클래스에 묶이기만 한다.
     */
    private static final String WRONG_TYPE = "WRONGTYPE";

    private final ReactiveStringRedisTemplate redisTemplate;

    @Override
    public Mono<Boolean> isKicked(UUID roomId, UUID userId) {
        String key = ChatRedisKeys.kicked(roomId);
        return redisTemplate.opsForSet()
                .isMember(key, userId.toString())
                // 일시 장애는 그대로 흘려보낸다 — 소비처의 fail-open 정책은 바뀌지 않는다.
                // 낫지 않는 쪽만 타입으로 갈라, 소비처가 "곧 복구될 실패"와 섞어 로그하지 않게 한다.
                .onErrorMap(ChatKickRedisRepository::isWrongType, e -> new KickStoreCorruptedException(key, e));
    }

    /** 감싸인 예외라 원인 사슬을 따라 내려가며 본다. cause가 자기 자신인 경우를 대비해 끊는다. */
    private static boolean isWrongType(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null && message.startsWith(WRONG_TYPE)) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
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
