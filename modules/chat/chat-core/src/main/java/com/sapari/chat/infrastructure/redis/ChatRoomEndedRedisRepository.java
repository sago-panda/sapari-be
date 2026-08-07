package com.sapari.chat.infrastructure.redis;

import java.time.Duration;
import java.util.UUID;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Repository;

import com.sapari.chat.domain.repository.ChatRoomEndedRepository;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * room:{roomId}:ended 마커 어댑터 — 종료 사실 기록(SET)·조회(EXISTS).
 */
@Repository
@RequiredArgsConstructor
public class ChatRoomEndedRedisRepository implements ChatRoomEndedRepository {

    /**
     * 마커 보관 기간.
     *
     * <p><b>하한</b>은 "가장 오래 살아남을 수 있는 룸 토큰보다 길 것"이다. 그보다 짧으면 마커가 먼저 사라져
     * 아직 유효한 토큰으로 다시 들어올 수 있다. 토큰 수명은 live 소유 설정이라 여기서 읽을 수 없으므로
     * 한참 위로 잡는다.
     *
     * <p><b>상한은 사실상 없다</b>: 끝난 방은 다시 라이브가 되지 않으므로(라이브 전이는 예정·준비 상태에서만
     * 일어난다) 마커가 오래 남아 정상 시청자를 막는 경우가 생기지 않는다. 남는 비용은 Redis 메모리뿐이라
     * 세션 키와 같은 값으로 맞춘다.
     */
    private static final Duration ENDED_TTL = Duration.ofHours(24);

    private static final String MARKER = "1";   // 값은 안 쓴다 — 키 존재 자체가 신호

    private final ReactiveStringRedisTemplate redisTemplate;

    @Override
    public Mono<Void> markEnded(UUID roomId) {
        return redisTemplate.opsForValue()
                .set(ChatRedisKeys.roomEnded(roomId), MARKER, ENDED_TTL)
                .then();
    }

    @Override
    public Mono<Boolean> isEnded(UUID roomId) {
        return redisTemplate.hasKey(ChatRedisKeys.roomEnded(roomId));
    }
}
