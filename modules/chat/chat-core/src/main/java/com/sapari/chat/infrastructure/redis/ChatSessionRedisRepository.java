package com.sapari.chat.infrastructure.redis;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import com.sapari.chat.domain.repository.ChatSessionRepository;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * chat:room:{roomId}:sessions HASH 어댑터 — 크로스 Pod 세션 집계.
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
     *
     * <p><b>줄이지 말 것</b>: 갱신은 입장(add) 때만 일어나고 기존 세션을 다시 등록하는 경로가 없다.
     * 그래서 만료 조건은 "방송이 길다"가 아니라 <b>마지막 입장 이후 이 값만큼 신규 입장이 없다</b>이다 —
     * 한산한 방일수록 먼저 걸리고, 걸리면 그 방송 내내 시청자 수가 어긋난다.
     * (주기적 재등록이 생기기 전까지는 이 여유가 유일한 방어다.)
     */
    private static final Duration SESSIONS_TTL = Duration.ofHours(24);

    /**
     * 등재와 TTL 설정을 한 번에 — 둘로 나누면 그 사이에 Pod가 죽거나 EXPIRE만 실패했을 때
     * <b>TTL 없는 키</b>가 남는다. 그 키를 받아줄 백스톱이 바로 TTL이라, 빠지면 폴백 자체가 사라진다.
     */
    private static final RedisScript<Long> ADD_SESSION = RedisScript.of("""
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
            redis.call('EXPIRE', KEYS[1], ARGV[3])
            return 1
            """, Long.class);

    @Override
    public Mono<Void> add(UUID roomId, String sessionId, UUID userId) {
        // 실패는 전파한다 — 호출자(register)가 "명부 등재 실패로 접속을 막지 않는다"를 이미 책임진다.
        return redisTemplate.execute(ADD_SESSION,
                        List.of(ChatRedisKeys.sessions(roomId)),
                        List.of(sessionId, userId.toString(), String.valueOf(SESSIONS_TTL.toSeconds())))
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
