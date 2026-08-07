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
     * <p><b>상한</b>은 잘못된 종료 신호의 피해 반경이다. 끝난 방이 다시 라이브가 되지는 않지만(라이브 전이는
     * 예정·준비 상태에서만 일어난다), 그 전제는 <b>받은 신호가 진짜 종료일 때만</b> 성립한다. 채널 페이로드에는
     * 진위를 확인할 수단이 없고 마커를 지우는 경로도 없어서, 오발행 한 건이 진행 중인 방을 이 시간만큼
     * 채팅 불능으로 만든다. 그래서 하한 대비 여유는 크게 두되 시간 단위로 늘리지는 않는다.
     */
    private static final Duration ENDED_TTL = Duration.ofMinutes(30);

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
