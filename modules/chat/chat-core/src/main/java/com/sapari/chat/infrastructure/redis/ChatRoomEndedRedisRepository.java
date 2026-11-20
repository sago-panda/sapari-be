package com.sapari.chat.infrastructure.redis;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Repository;

import com.sapari.chat.domain.repository.ChatRoomEndedRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * chat:room:{roomId}:ended 마커 어댑터 — 종료 사실 기록(SET)·조회(EXISTS, live 의 종료 키도 함께 본다).
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
     * <p><b>하한은 세션 수명({@code chat.session.max-lifetime})이다.</b> 종료 신호는 Pub/Sub 이라 구독이
     * 끊겨 있던 Pod 는 그것을 통째로 놓치고, 그 Pod 의 세션은 종료 뒤에도 수명이 다할 때까지 남는다.
     * 그 세션을 막는 것은 전송 경로의 재확인({@code EntryGate.isRoomAlive})뿐이고 그 재확인이 읽는 것이
     * 이 마커다(live 의 종료 키도 함께 읽지만 그 키는 없을 수 있다 — {@link #isEnded}). 마커가 수명보다
     * 먼저 사라지면 그 뒤의 전송은 종료 판정을 통과한다. 수명 지터는 아래로만
     * 흩으므로 지터 전 수명이 그대로 하한이다. 새 입장은 이 하한에 따로 걸리지 않는다 — 입장도 이 마커를
     * 읽고, 토큰으로 입장할 수 있는 기간은 {@code min(exp, 수명)} 이라 수명 안에 들어온다.
     *
     * <p>수명은 streaming-app 설정이라 여기서 읽을 수 없다. 대신 그 앱이 기동 때 "수명 ≤ 이 값" 을
     * 검사하고 어기면 기동을 멈춘다({@code WebSocketConfig.sessionTimeouts}). 그래서 이 값을 내리면
     * 기본 설정의 streaming-app 이 기동하지 않는다. 기본 수명 60분에 10분을 더한 것은 고른 여유다 —
     * 측정한 값이 아니다.
     *
     * <p><b>상한</b>은 잘못된 종료 신호의 피해 반경이다. 끝난 방이 다시 라이브가 되지는 않지만(라이브 전이는
     * 예정·준비 상태에서만 일어난다), 그 전제는 <b>받은 신호가 진짜 종료일 때만</b> 성립한다. 채널 페이로드에는
     * 진위를 확인할 수단이 없어서, 오발행 한 건이 진행 중인 방을 이 시간만큼 채팅 불능으로 만든다.
     * 앱 밖에서 들어온 오발행은 해제 엔드포인트로 지울 수 있고, 앱 경로의 발행은 방을 먼저
     * {@code Ended} 로 커밋한 뒤에 나간다({@code ClearRoomEndedMarkerService} 가 그 사슬을 적는다).
     * 그래서 하한 위 여유는 작게 둔다.
     */
    // public: streaming-app 이 기동 때 "세션 수명 ≤ 이 값" 을 검사한다(위 둘째 단락). 같은 패키지의
    // 블로킹 해제 어댑터도 되돌림에 이 값을 쓴다. 복사하면 한쪽을 고칠 때 되돌린 마커가 원래보다
    // 짧거나 길게 살아난다 — 그 사고를 ChatKickRecorder 가 실측으로 적어 둔 적이 있다(상한 상수 복사).
    public static final Duration ENDED_TTL = Duration.ofMinutes(70);

    static final String MARKER = "1";   // 값은 안 쓴다 — 키 존재 자체가 신호

    private final ReactiveStringRedisTemplate redisTemplate;

    @Override
    public Mono<Void> markEnded(UUID roomId) {
        return redisTemplate.opsForValue()
                .set(ChatRedisKeys.roomEnded(roomId), MARKER, ENDED_TTL)
                // 성공도 남긴다 — 이 한 줄이 그 방을 TTL 동안 입장·전송 불가로 만든다. 잘못된 신호였을 때
                // "언제 어느 방이 잠겼는지"를 되짚을 근거가 이것뿐이고, 해제는 키를 직접 지우는 수밖에 없다.
                // 해제 안내가 바뀌었다 — 이제 엔드포인트가 있다(DELETE /api/v1/lives/{id}/chat/ended).
                // 다만 그것은 그 방이 live 정본에서 진행 중일 때만 지운다. 이 로그가 남은 사고가
                // 앱 경로의 오발행(배치 오판 등)이면 방이 이미 Ended 라 그 엔드포인트가 거부하므로,
                // 그때는 여전히 DEL 뿐이다 — 둘을 함께 적는다.
                .doOnSuccess(ignored -> log.info("방 종료 마커 기록 — 이 방은 만료까지 입장·전송이 막힌다 "
                        + "roomId={} ttl={}분 (해제: 진행 중인 방이면 DELETE "
                        + "/api/v1/lives/{}/chat/ended, 이미 Ended 면 DEL {})",
                        roomId, ENDED_TTL.toMinutes(), roomId, ChatRedisKeys.roomEnded(roomId)))
                .then();
    }

    /**
     * chat 마커 또는 live 의 종료 키 중 하나라도 있으면 끝난 방이다.
     *
     * <p>chat 마커는 종료 신호(Pub/Sub)를 받은 Pod 가 쓰고, 공유 Redis 라 신호를 놓친 Pod 도 그것을 읽는다.
     * 그래서 마커가 비는 것은 <b>아무도 쓰지 못했거나, 쓴 뒤 해제 경로가 지웠을 때</b>다. 앞의 것은 발행 실패,
     * 그 순간 전 Pod 의 구독 단절, 마커 쓰기 실패이고, 뒤의 것은 {@code ClearRoomEndedMarkerService} 가 검증과
     * 삭제 사이에 끝난 방의 맞는 마커를 지우는 경우다(되돌림이 실패하면 남는다). live 의 종료 키는 신호를
     * 거치지 않는 기록이라 둘 다 메운다 — 뒤의 경우 live 는 발행 전에 키를 쓰므로, 그 발행으로 쓰인 마커가
     * 지워질 때 키는 이미 있다(쓰기가 실패하지 않았다면).
     *
     * <p>live 키가 <b>없다고</b> 진행 중이라는 뜻은 아니다. live 는 미디어 정리 뒤에 키를 쓰고 쓰기 실패를
     * 재시도하지 않는다. 그래서 두 키는 둘 중 하나만 있어도 종료이고, 어느 쪽도 다른 쪽을 대신하지 않는다.
     */
    @Override
    public Mono<Boolean> isEnded(UUID roomId) {
        return redisTemplate.countExistingKeys(List.of(ChatRedisKeys.roomEnded(roomId), liveRoomEnded(roomId)))
                .map(count -> count > 0);
    }

    /**
     * live 가 소유하는 종료 키. 값(종료 시각 epoch millis)은 읽지 않는다 — 키가 있는 것이 신호다.
     *
     * <p>live 쪽 정의({@code LiveRedisKeys.roomEnded})가 package-private 이라 문자열을 복사해 둔다. 두
     * 정의를 함께 검사하는 장치는 없으니, 한쪽 이름을 바꾸면 다른 쪽도 같이 바꿔야 한다. live 채널 이름을
     * {@code RedisLiveRoomEndedSource} 가 따로 들고 있는 것과 같은 이유로 {@link ChatRedisKeys} 에 두지 않는다.
     */
    private static String liveRoomEnded(UUID roomId) {
        return "live:room:ended:" + roomId;
    }
}
