package com.sapari.chat.infrastructure.redis;

import java.time.Duration;
import java.util.UUID;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Repository;

import com.sapari.chat.domain.repository.ChatRoomEndedRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * chat:room:{roomId}:ended 마커 어댑터 — 종료 사실 기록(SET)·조회(EXISTS).
 *
 * <p>이 키는 강퇴 SET과 달리 타입 충돌에 노출되지 않는다 — SET은 기존 타입을 덮어쓰고 EXISTS는 타입을
 * 보지 않아, 남이 같은 이름을 먼저 써도 WRONGTYPE이 나지 않는다.
 */
@Slf4j
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
     *
     * <p>오발행이 사람 실수만은 아니다 — 방치된 Live를 정리하는 <b>배치도 이 신호를 발행</b>하므로
     * 자동으로 잘못 판정될 수 있다. live 쪽에 전량 오판을 막는 가드는 있지만 방 단위 오판은 남는다.
     * 이 상한이 그 경우의 회복 시간이 된다.
     */
    private static final Duration ENDED_TTL = Duration.ofMinutes(30);

    private static final String MARKER = "1";   // 값은 안 쓴다 — 키 존재 자체가 신호

    private final ReactiveStringRedisTemplate redisTemplate;

    @Override
    public Mono<Void> markEnded(UUID roomId) {
        return redisTemplate.opsForValue()
                .set(ChatRedisKeys.roomEnded(roomId), MARKER, ENDED_TTL)
                // 성공도 남긴다 — 이 한 줄이 그 방을 TTL 동안 입장·전송 불가로 만든다. 잘못된 신호였을 때
                // "언제 어느 방이 잠겼는지"를 되짚을 근거가 이것뿐이고, 해제는 키를 직접 지우는 수밖에 없다.
                .doOnSuccess(ignored -> log.info("방 종료 마커 기록 — 이 방은 만료까지 입장·전송이 막힌다 "
                        + "roomId={} ttl={}분 (해제: DEL {})", roomId, ENDED_TTL.toMinutes(),
                        ChatRedisKeys.roomEnded(roomId)))
                .then();
    }

    @Override
    public Mono<Boolean> isEnded(UUID roomId) {
        return redisTemplate.hasKey(ChatRedisKeys.roomEnded(roomId));
    }
}
