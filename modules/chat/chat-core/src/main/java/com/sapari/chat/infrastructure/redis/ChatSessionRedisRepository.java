package com.sapari.chat.infrastructure.redis;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import com.sapari.chat.domain.repository.ChatSessionRepository;
import com.sapari.global.time.TimeProvider;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * chat:room:{roomId}:sessions HASH 어댑터 — 크로스 Pod 세션 집계.
 *
 * <p><b>값에 마지막 확인 시각을 담는다</b>({@code userId:epochMillis}). Pod 가 크래시하면 그 Pod 의
 * 세션은 해제 경로를 타지 못해 명부에 남고, 그걸 그대로 세면 그 방의 시청자 수가 <b>방송 내내</b>
 * 부풀어 있다. 재등록이 끊긴 항목을 집계에서 빼는 것이 그 교정이다.
 *
 * <p><b>필드 단위 TTL({@code HEXPIRE})을 쓰지 않는다.</b> 그쪽이 더 단순하고(타임스탬프 파싱도
 * 유계 산술도 필요 없다) 이 머신의 {@code redis:7} 은 7.4.11 로 그 명령을 갖고 있다(실측). 하지만
 * 테스트가 쓰는 태그가 <b>떠 있는 태그</b>라 7.2 를 당긴 머신에서는 없고, 운영 Redis 버전은 이
 * 저장소에서 확인할 수 없다(인프라 저장소 소관) — "로컬 초록, 운영 깨짐" 이 되는 모양이다.
 * 7.4 이상이 보장되는 날 이쪽을 걷어내는 것이 맞다.
 *
 * <p>낡음 판정에 <b>읽는 쪽의 벽시계</b>를 쓴다. 쓰는 Pod 와 읽는 Pod 가 다를 수 있어 시계 오차가
 * 끼는데, 재등록 주기와 판정 상한 사이에 세 주기만큼 여유가 있어 초 단위 오차는 삼켜진다.
 * 판정을 Lua 로 옮기면 Redis 시계 하나만 쓰게 되지만, 그러면 전수 순회가 단일 스레드에서 돌아
 * 비용이 클라이언트에서 Redis 로 옮겨 간다 — 지금 그 비용이 문제가 아니라는 것을 쟀으므로 옮기지 않는다.
 * count는 HLEN(탭 수)이 아니라 HVALS distinct(고유 유저 수)다 — 같은 유저 멀티탭은 1로 센다(시청자 수는 사람 수지 창 수가 아니다).
 *
 * <p><b>HLEN 으로 바꾸는 것은 최적화가 아니라 숫자를 틀리게 하는 것이다.</b> 이 해시는 탭마다 한 행이라
 * HLEN 은 멀티탭 배수만큼 부풀고(실측: 1인 2탭이면 2배), 그 교체는 저장소 테스트가 바로 잡는다.
 * O(N) 이 눈에 걸려 손대고 싶어지는 자리라 적어 둔다 — 비용 쪽은 이미 호출자가 캐시와 비행 병합으로
 * 닫았고, 설계 상한의 5배에서도 상한 아래라는 것을 재서 확인했다({@code ChatSessionRegistry}).
 */
@Repository
@RequiredArgsConstructor
public class ChatSessionRedisRepository implements ChatSessionRepository {

    private final ReactiveStringRedisTemplate redisTemplate;

    /**
     * 벽시계 — 값에 담는 "마지막 확인 시각" 과 낡음 판정의 기준이다.
     *
     * <p>경과 시간 측정이 아니라 <b>Pod 사이에 공유되는 절대 시각</b>이라 {@code nanoTime} 이 아니다.
     */
    private final TimeProvider timeProvider;

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
     * 재등록 주기 — 호출자가 이보다 드물게 {@code heartbeat} 하면 그 세션이 집계에서 빠진다.
     *
     * <p>생존 확인 간격(기본 30초)과 같은 수다. 같은 질문("이 세션이 아직 있는가")을 두 주기로
     * 묻는 것이 이상하기 때문이고, 그래서 정상 세션은 생존 확인이 끊기 전에 반드시 재등록된다.
     */
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(30);

    /**
     * 이만큼 재등록이 없으면 집계에서 뺀다.
     *
     * <p><b>주기의 4배다 — 재등록 3회를 놓쳐도 살아 있는 시청자가 사라지지 않는다.</b> 2배로 잡으면
     * 한 번 놓친 세션이 곧바로 집계에서 빠지는데, Redis 가 잠깐 흔들린 순간이 정확히 그 모양이다.
     * 같은 함정을 생존 확인에서 이미 한 번 겪었다(첫 창에서 2회 유실이 멀쩡한 세션을 끊었다).
     *
     * <p>치우지는 않는다 — 낡은 항목의 제거는 TTL 과 방 종료({@code clearRoom})가 맡는다. 읽기가
     * 쓰기를 하면 집계 조회가 쓰기 경로가 되고, 그 경로는 입장마다 불린다.
     */
    private static final Duration STALE_AFTER = HEARTBEAT_INTERVAL.multipliedBy(4);

    static {
        // 가드를 상수 옆에 둔다 — 둘은 한 결정이고, 한쪽만 바꾸면 살아 있는 시청자가 집계에서
        // 사라지거나(상한이 짧아짐) 좀비가 오래 남는다(주기가 길어짐). 사람 검토에 맡기지 않는다.
        if (STALE_AFTER.compareTo(HEARTBEAT_INTERVAL.multipliedBy(3)) < 0) {
            throw new IllegalStateException(
                    "낡음 판정 상한이 재등록 주기의 3배보다 짧다 — 재등록 2회 유실에 살아 있는 세션이 "
                            + "집계에서 빠진다: 상한=" + STALE_AFTER + " 주기=" + HEARTBEAT_INTERVAL);
        }
    }

    /** 값 구분자 — {@code userId:epochMillis}. UUID 와 10진수 둘 다 이 문자를 쓰지 않는다. */
    private static final char VALUE_SEPARATOR = ':';

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
        return upsert(roomId, sessionId, userId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>등재와 <b>같은 쓰기</b>다 — 그게 요점이다. TTL 만 touch 하면 Redis 데이터 유실 뒤에 사라진
     * 항목이 되살아나지 않아 그 방송 내내 시청자 수가 모자란다.
     */
    @Override
    public Mono<Void> heartbeat(UUID roomId, String sessionId, UUID userId) {
        return upsert(roomId, sessionId, userId);
    }

    @Override
    public Duration heartbeatInterval() {
        return HEARTBEAT_INTERVAL;
    }

    private Mono<Void> upsert(UUID roomId, String sessionId, UUID userId) {
        return redisTemplate.execute(ADD_SESSION,
                        List.of(ChatRedisKeys.sessions(roomId)),
                        List.of(sessionId, encode(userId), String.valueOf(SESSIONS_TTL.toSeconds())))
                .then();
    }

    /** {@code userId:epochMillis} — 마지막 확인 시각을 값에 담아 낡음 판정의 근거로 쓴다. */
    private String encode(UUID userId) {
        return userId + String.valueOf(VALUE_SEPARATOR) + timeProvider.now().toEpochMilli();
    }

    @Override
    public Mono<Void> remove(UUID roomId, String sessionId) {
        return redisTemplate.opsForHash()
                .remove(ChatRedisKeys.sessions(roomId), sessionId)
                .then();
    }

    @Override
    public Mono<Long> count(UUID roomId) {
        Instant staleBefore = timeProvider.now().minus(STALE_AFTER);
        return redisTemplate.opsForHash()
                .values(ChatRedisKeys.sessions(roomId))
                .map(String::valueOf)
                .mapNotNull(value -> liveUserId(value, staleBefore))
                .distinct()
                .count();
    }

    /**
     * 값에서 살아 있는 유저 id 를 뽑는다. 낡았으면 {@code null} 로 집계에서 빠진다.
     *
     * <p><b>구분자가 없는 값은 살아 있는 것으로 본다.</b> 그 모양은 이 형식 이전에 쓰인 항목이고,
     * 롤링 배포 중에는 구 버전 Pod 가 계속 그렇게 쓴다 — 낡은 것으로 치면 배포 중에 <b>그 Pod 의
     * 시청자 전원이 집계에서 사라진다</b>. 반대 방향(좀비가 TTL 까지 남는 것)은 배포가 끝나면 스스로
     * 낫는다.
     *
     * <p>타임스탬프가 숫자가 아닌 값도 같은 이유로 살아 있는 것으로 본다 — 깨진 항목 하나가
     * 집계를 줄이는 쪽으로 기울면 "시청자가 없다" 와 구분되지 않는다.
     */
    private static String liveUserId(String value, Instant staleBefore) {
        int separator = value.lastIndexOf(VALUE_SEPARATOR);
        if (separator < 0) {
            return value;
        }
        String userId = value.substring(0, separator);
        long lastSeenMillis;
        try {
            lastSeenMillis = Long.parseLong(value.substring(separator + 1));
        } catch (NumberFormatException e) {
            return userId;
        }
        return lastSeenMillis < staleBefore.toEpochMilli() ? null : userId;
    }

    @Override
    public Mono<Void> clearRoom(UUID roomId) {
        return redisTemplate.delete(ChatRedisKeys.sessions(roomId)).then();
    }
}
