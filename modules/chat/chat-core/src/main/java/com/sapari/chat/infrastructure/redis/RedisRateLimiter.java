package com.sapari.chat.infrastructure.redis;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.sapari.chat.application.port.ChatMetrics;
import com.sapari.chat.application.port.RateLimitDegradation;
import com.sapari.chat.application.port.RateLimitResult;
import com.sapari.chat.application.port.RateLimiter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * ratelimit:chat:{userId} — 평균 {@link #EMISSION_INTERVAL} 에 1건, 연속 {@link #BURST} 건(GCRA).
 *
 * <p><b>왜 버스트가 있는가.</b> 고정 창(3초에 1건)에서는 "안녕하세요" 다음에 바로 "질문 있어요" 를
 * 보내는 평범한 두 줄이 거부된다. GCRA 는 평균은 같게 두고 쉬었던 만큼 몰아 보낼 여유를 준다 — 오래
 * 조용했던 사용자는 3건을 연달아 보낼 수 있고, 계속 보내면 3초에 1건으로 수렴한다.
 *
 * <p><b>판정은 Redis 스크립트 한 번이다.</b> 키에 다음 허용 시각(TAT, ms)을 두고 읽기·판정·쓰기를
 * 스크립트 안에서 끝내므로 동시 요청이 둘 다 마지막 자리를 가져가지 못한다. 시각은 Redis {@code TIME}
 * 이다 — Pod 마다 벽시계가 달라도 같은 키를 같은 시계로 판정한다. 거부 응답의 남은 시간도 같은 스크립트가
 * 돌려줘서 거부 뒤에 따로 묻는 왕복이 없다.
 *
 * <p>거부는 TAT 를 건드리지 않는다 — 막힌 채 계속 두드려도 해제가 미뤄지지 않는다.
 *
 * <p>Redis 장애 시에는 <b>프로세스 로컬 GCRA 로 강등</b>한다 — 끊긴 Redis 뿐 아니라 <b>답이 느린</b>
 * Redis 도 포함한다({@link #REDIS_CALL_LIMIT}). 같은 규칙을 이 Pod 안에서만 적용한다. 전면 통과였을
 * 때는 Redis 가 죽은 동안 회선 속도로 미는 클라 하나가 전부 통과하고 그 프레임마다 저장이 일어났다.
 *
 * <p>강등이 Redis 판정보다 느슨한 지점은 둘이다. 하나는 <b>Pod 경계</b>다 — 사용자의 탭이 서로 다른 Pod 에
 * 붙으면 각 Pod 가 자기 몫을 센다. WebSocket 세션은 한 Pod 에 고정이라 세션 하나는 같은 규칙을 보고, 가장
 * 느슨한 경우도 Pod 수에 비례해 유계다. 다른 하나는 <b>강등 전이</b>다 — Redis 판정과 로컬 판정은 TAT 를
 * 주고받지 않아서, 강등이 시작되면 방금 Redis 에서 막힌 사용자에게도 로컬 버스트({@link #BURST}건)가 새로
 * 열린다. 장애가 끝나면 Redis 판정이 다시 권위를 갖는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisRateLimiter implements RateLimiter {

    /** 평균 간격 — 계속 보내는 사용자가 수렴하는 속도다. */
    static final Duration EMISSION_INTERVAL = Duration.ofSeconds(3);

    /** 쉬었던 사용자가 연달아 보낼 수 있는 건수. */
    static final int BURST = 3;

    /** GCRA 허용 오차 — 버스트 건수만큼의 간격에서 지금 보내는 한 칸을 뺀 것이다. */
    private static final long TOLERANCE_MILLIS = (BURST - 1) * EMISSION_INTERVAL.toMillis();

    /**
     * 키에는 다음 허용 시각(TAT, ms)이 있다. 없거나 지났으면 지금으로 본다.
     *
     * <p>{@code TAT - 허용 오차} 가 아직 미래면 거부하고 그때까지 남은 ms 를 돌려준다 — 키는 그대로다.
     * 허용이면 TAT 를 한 간격 밀고, 키의 수명은 그 TAT 까지다(지나면 처음 상태와 같다).
     *
     * <p>숫자가 아닌 값(옛 고정 창이 쓰던 {@code "1"} 포함)은 {@code tonumber} 가 작은 수나 nil 로
     * 읽어 "지났다" 와 같아진다 — 롤링 배포 중 옛 키가 남아 있어도 새 판정이 막히지 않는다.
     */
    private static final RedisScript<List> ACQUIRE = RedisScript.of("""
            local t = redis.call('TIME')
            local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            local interval = tonumber(ARGV[1])
            local tolerance = tonumber(ARGV[2])
            local tat = tonumber(redis.call('GET', KEYS[1]))
            if tat == nil or tat < now then
                tat = now
            end
            local allowAt = tat - tolerance
            if allowAt > now then
                return {0, allowAt - now}
            end
            local nextTat = tat + interval
            redis.call('SET', KEYS[1], nextTat, 'PX', nextTat - now)
            return {1, 0}
            """, List.class);

    /**
     * Redis 왕복 상한 — <b>느린 Redis 를 죽은 Redis 로 수렴시킨다.</b>
     *
     * <p>이게 없으면 강등이 <b>에러 신호에만</b> 걸린다. Redis 가 패킷을 떨구면(파티션·과부하) 커맨드가
     * 클라이언트 상한까지 pending 이고, 그 동안 {@code onErrorResume} 이 돌지 않아 로컬 판정이 발화하지
     * 않는다. 전송 경로가 {@code concatMap} 으로 직렬화돼 있어 <b>그 세션의 채팅이 통째로 멈춘다</b>.
     *
     * <p>클라이언트 상한은 1초다({@code RedisTimeoutConfig}). 이 200ms 는 따뜻한 경로의 더 조인 예산이다.
     * 판정이 스크립트 한 번이라 {@code tryAcquire} 한 건이 쓰는 Redis 예산도 이 값 하나다.
     *
     * <p>⚠️ <b>그 200ms 는 따뜻한 커넥션에서만의 경계다.</b> 공유 커넥션이 아직 수립되지 않은 상태에서는
     * 연산자 상한이 무장되지 않는다 — {@code ReactiveRedisTemplate} 이 커넥션 획득을 {@code subscribe()}
     * 경로에서 동기로 하고 {@code .timeout()} 은 구독이 반환된 뒤에 무장된다. 그 구간은 클라이언트 상한이
     * 묶는다. 블랙홀 리스너로 실측(고정 창 시절의 {@code SET NX} 호출로 잰 값이다):
     *
     * <pre>
     * 따뜻한 커넥션                       210ms 내외   ← 연산자 상한이 듣는다
     * 차가운 커넥션 + 클라이언트 기본값   60,245ms     ← .timeout(200ms)·.block(1s) 둘 다 못 끊는다
     * 차가운 커넥션 + 1초 클라이언트 상한  1,115ms     ← RedisTimeoutConfig 적용 후
     * </pre>
     *
     * <p>⚠️ <b>이 상한이 고치는 것은 이 호출 하나다.</b> 같은 모양의 호출이 전송 경로의 강퇴·밴 조회와
     * 입장 게이트의 종료 마커 조회에 있다. 그렇다고 이 200ms 를 거기 복사하지 말 것 — 상한을 걸면 그
     * 자리의 실패 방향이 드러나는데 그 방향이 자리마다 다르다:
     * <ul>
     *   <li>여기: 느리면 <b>로컬 판정으로 떨어진다</b> — 같은 방향의 유계 강등이다.
     *   <li>전송 경로의 강퇴·밴 조회: 느리면 <b>그 전송을 거절한다</b> — {@code modules/chat/AGENTS.md}
     *       가 그것을 의도된 결정으로 따로 다룬다.
     *   <li>종료 마커 조회: 입장에서는 느리면 <b>끝난 방에 입장을 허용하고</b>, 전송 재확인에서는
     *       <b>그 전송을 거절한다</b> — 한 키에 두 방향이다.
     *   <li>시청자 수 조회({@code ChatSessionRedisRepository.count}): 느리면 <b>시청자 수 없이
     *       입장시킨다</b>. 연산자 상한이 없어 클라이언트 상한(1초)까지 기다리고, 거기에 비행 병합이 붙어
     *       그 방 신규 입장자가 <b>같은</b> 멈춘 왕복에 묶인다.
     *   <li>메시지 저장({@code SendChatService} → Mongo): 느리면 <b>세션의 전송이 통째로 멈춘다</b> —
     *       지금은 {@code chat.mongo.operation-timeout}(기본 2s)이 끊고 {@code chat.persist.timeout} 이
     *       규모를 든다.
     * </ul>
     * 다섯 자리의 적정 값과 정책은 각각 따로 근거를 가져야 한다. 유추로 확장할 성질이 아니다.
     */
    private static final Duration REDIS_CALL_LIMIT = Duration.ofMillis(200);

    /**
     * 강등 로그 간격. <b>건수가 아니라 경과시간</b>으로 솎아낸다 — 건수 기준(N건당 1줄)은 카운터가
     * 프로세스 생애 누적이라 두 번째 이후의 짧은 장애가 통째로 침묵한다(예: 누적 251~290이면 한 줄도 안 남음).
     * 규모가 작을수록 안 남는 역방향이라, 진단이 정작 어려운 산발 장애를 놓친다.
     */
    private static final long DEGRADED_LOG_INTERVAL_NANOS = Duration.ofSeconds(10).toNanos();

    private static final long EMISSION_INTERVAL_NANOS = EMISSION_INTERVAL.toNanos();

    private static final long TOLERANCE_NANOS = Duration.ofMillis(TOLERANCE_MILLIS).toNanos();

    private final ReactiveStringRedisTemplate redisTemplate;

    /**
     * 관측 포트. 아래 카운터는 프로세스 안에만 있어서 <b>레이트리밋이 강등된 구간을 밖에서 알 수
     * 없었다</b> — 강등은 허용량을 Pod 수만큼 늘리는 선택이라, 그 선택이 얼마나 오래 유효했는지가
     * 집행 정확도의 전부다. 로그 솎아내기용 카운터는 그대로 둔다(로그 문장이 누적 건수를 싣는다).
     */
    private final ChatMetrics metrics;

    /** 강등 누적 횟수(= Redis 가 답하지 못해 로컬 판정을 쓴 횟수). 로그를 솎아내도 규모는 남기기 위한 카운터. */
    private final AtomicLong degradedCount = new AtomicLong();

    /** 마지막으로 강등을 로깅한 시각. 첫 발생이 반드시 남도록 간격만큼 과거로 초기화한다. */
    private final AtomicLong lastDegradedLogNanos = new AtomicLong(System.nanoTime() - DEGRADED_LOG_INTERVAL_NANOS);

    /**
     * Redis 장애 구간의 로컬 판정 — userId → 다음 허용 시각(TAT, {@code nanoTime}).
     *
     * <p>항목 수는 "이 Pod 에 붙어 장애 구간에 전송한 사용자" 수를 넘지 않는다. userId 는 인증된 값이라
     * 임의로 늘릴 수 없고, 그 상한은 세션 맵과 같은 것이 민다.
     */
    private final ConcurrentHashMap<UUID, Long> localTatNanos = new ConcurrentHashMap<>();

    /** 마지막으로 지난 항목을 쓸어낸 시각. */
    private final AtomicLong lastSweepNanos = new AtomicLong(System.nanoTime());

    @Override
    public Mono<RateLimitResult> tryAcquire(UUID userId) {
        String key = ChatRedisKeys.rateLimit(userId);
        return redisTemplate.execute(ACQUIRE, List.of(key),
                        List.of(Long.toString(EMISSION_INTERVAL.toMillis()), Long.toString(TOLERANCE_MILLIS)))
                .next()
                .timeout(REDIS_CALL_LIMIT)
                .map(RedisRateLimiter::toResult)
                .onErrorResume(err -> {
                    // Redis가 죽으면 전 사용자의 모든 전송이 이 경로를 탄다. 건당 스택트레이스를 남기면
                    // 초당 수백 줄이 쌓여 정작 원인(Redis 장애) 로그를 묻는다. 그래서 간격으로 솎아낸다.
                    // userId는 뺀다: 키 하나의 문제가 아니라 사실상 전역 장애이고, 표본 하나에 찍힌 id는
                    // "이 사용자 문제"로 오독을 부른다. 대신 예외를 그대로 실어 위치를 남긴다.
                    metrics.rateLimitDegraded(RateLimitDegradation.LOCAL_WINDOW);
                    long count = degradedCount.incrementAndGet();
                    if (shouldLogDegraded()) {
                        // WARN — 집행이 사라진 것이 아니라 정확도만 잃었다(Pod 로컬 판정). 사람이
                        // 조치할 것은 Redis 쪽이고 그건 Redis 자체 알림의 몫이다. 이 경로의 규모는
                        // chat.ratelimit.degraded{mode=local_window} 가 비율로 들고 있다.
                        log.warn("rate limit Redis 장애 — 로컬 판정으로 강등(누적 {}건)", count, err);
                    }
                    return Mono.just(localAcquire(userId, System.nanoTime()));
                });
    }

    /** 스크립트 응답 {허용 여부, 남은 ms} → 결과. */
    private static RateLimitResult toResult(List<?> reply) {
        boolean allowed = ((Number) reply.get(0)).longValue() == 1;
        long remainingMillis = ((Number) reply.get(1)).longValue();
        return allowed
                ? new RateLimitResult(true, 0)
                : new RateLimitResult(false, ceilSeconds(Duration.ofMillis(remainingMillis).toNanos()));
    }

    /**
     * 남은 시간 → 클라이언트에 알려 줄 초. 올림 + 최소 1.
     *
     * <p>올리는 쪽이 안전하다 — 내리면 아직 막혀 있는데 짧게 알려 줘서 클라가 일찍 재시도하고 거부
     * 왕복이 한 번 더 붙는다. Redis 판정과 로컬 판정이 같은 함수를 쓴다.
     */
    private static long ceilSeconds(long remainingNanos) {
        return Math.max(1, (remainingNanos + 999_999_999L) / 1_000_000_000L);
    }

    /**
     * 마지막 로그로부터 간격이 지났으면 true. 경쟁 시 한 스레드만 통과한다(스로틀이 곧 중복 억제).
     *
     * <p>CAS 에 지는 쪽은 조용히 넘어간다 — 그래도 <b>카운터는 이미 올라가 있어</b> 규모는 남는다.
     * 솎기가 지우는 것은 줄 수지 사건 수가 아니다.
     *
     * <p>{@code System.nanoTime()} 을 직접 쓴다. ArchUnit 의 TimeProvider 규칙이 보는 범위는
     * {@code ..application..}·{@code ..domain..} 이고 이 클래스는 infrastructure 라 밖이다. 더
     * 중요하게 <b>재는 것이 경과 시간이지 시각이 아니다</b> — {@code TimeProvider} 는 벽시계라 NTP
     * 점프에 흔들린다. 로컬 판정도 같은 이유로 {@code nanoTime} 이다.
     */
    private boolean shouldLogDegraded() {
        long now = System.nanoTime();
        long last = lastDegradedLogNanos.get();
        return now - last >= DEGRADED_LOG_INTERVAL_NANOS && lastDegradedLogNanos.compareAndSet(last, now);
    }

    /**
     * Redis 가 답하지 못할 때의 판정 — Redis 스크립트와 같은 GCRA 를 이 Pod 안에서 적용한다.
     *
     * <p>{@code compute}는 키 단위로 원자적이다. 같은 사용자의 탭 둘이 이 Pod 에 붙어 동시에 들어와도
     * 마지막 자리를 둘 다 가져가지 않는다 — Redis 경로가 스크립트로 얻던 성질을 여기서도 지킨다. 읽고 나서
     * 쓰는 모양으로 적으면 그 사이에 둘 다 "자리가 있다"를 보고 통과한다.
     *
     * <p>시각을 인자로 받는다(테스트 진입점) — 다시 허용되는지, 지난 항목이 실제로 쓸려 나가는지를 재려면
     * 몇 초를 기다려야 하는데, 그 기다림이 테스트에 들어가면 느려서 아무도 안 돌리거나 경계에서 흔들린다.
     * 운영 호출부는 {@code System.nanoTime()} 하나뿐이다.
     */
    RateLimitResult localAcquire(UUID userId, long now) {
        sweepExpiredLocalEntries(now);
        // 람다 안에서 판정 결과를 돌려줄 자리가 없어 1칸 배열에 담는다 — 거부면 남은 나노초,
        // 허용이면 0 이 그대로 남는다(0 이 곧 "허용"이다).
        long[] remainingNanos = new long[1];
        localTatNanos.compute(userId, (key, storedTat) -> {
            long tat = storedTat == null || storedTat - now < 0 ? now : storedTat;
            long allowAt = tat - TOLERANCE_NANOS;
            if (allowAt - now > 0) {
                remainingNanos[0] = allowAt - now;
                // 밀지 않는다 — 거부가 TAT 를 밀면 계속 두드리는 클라가 스스로 영구 차단된다.
                return storedTat;
            }
            return tat + EMISSION_INTERVAL_NANOS;
        });
        return remainingNanos[0] == 0
                ? new RateLimitResult(true, 0)
                : new RateLimitResult(false, ceilSeconds(remainingNanos[0]));
    }

    /**
     * 지난 항목을 쓸어낸다 — 간격마다 한 번만.
     *
     * <p>항목은 TAT 가 지나면 처음 상태와 같아 쓸모가 없는데 스스로 사라지지 않는다. 호출마다 쓸면 O(n) 이
     * 프레임마다 붙어 <b>정작 막으려는 과부하 구간에서 가장 비싸진다.</b> 그래서 간격으로 솎는다 — 위 로그
     * 스로틀과 같은 방식이고, 경쟁 시 한 스레드만 통과하는 것도 같다.
     */
    private void sweepExpiredLocalEntries(long now) {
        long last = lastSweepNanos.get();
        if (now - last < EMISSION_INTERVAL_NANOS || !lastSweepNanos.compareAndSet(last, now)) {
            return;
        }
        localTatNanos.values().removeIf(tat -> tat - now <= 0);
    }

    /**
     * 추적 중인 로컬 항목 수 (테스트 진입점) — 지난 항목이 실제로 풀리는지 재는 데 쓴다.
     *
     * <p>쓸기는 강등 경로에서만 돌므로 <b>마지막 코호트는 다음 강등 호출에서</b> 풀린다. Redis 가
     * 살아난 뒤로는 그 호출이 오지 않으니 그 항목들은 Pod 수명 내내 남는다 — 유계이고 소량이다
     * (키는 서명 검증된 userId 뿐).
     */
    int trackedLocalEntries() {
        return localTatNanos.size();
    }
}
