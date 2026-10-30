package com.sapari.chat.infrastructure.redis;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;

import com.sapari.chat.application.port.RateLimitResult;
import com.sapari.chat.application.port.RateLimiter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * ratelimit:chat:{userId} 3초 fixed window — SET NX EX 단일 원자 연산이라
 * 동시 요청 TOCTOU race가 없다(둘 중 하나만 NX 획득). 거부는 잔여 TTL만 읽으므로 윈도우를 연장하지 않는다.
 *
 * <p>Redis 장애 시에는 <b>프로세스 로컬 창으로 강등</b>한다 — 끊긴 Redis 뿐 아니라 <b>답이 느린</b>
 * Redis 도 포함한다({@link #REDIS_CALL_LIMIT} 참고. 상한이 없으면 강등이 에러 신호에만 걸려,
 * 패킷을 떨구는 장애에서는 60초를 기다린다) — 같은 3초 고정 창을 이 Pod 안에서만 적용한다.
 * 채팅 가용성이 레이트리밋 엄격성보다 우선이지만, 그것이 "상한을 지운다"는 뜻일 이유는 없다. 전면 통과였을
 * 때는 Redis 가 죽은 동안 회선 속도로 미는 클라 하나가 창 없이 전부 통과하고 그 프레임마다 저장이 일어났다.
 *
 * <p>강등 창이 Redis 창보다 느슨한 지점은 <b>Pod 경계</b>다 — 사용자의 탭이 서로 다른 Pod 에 붙으면 각
 * Pod 가 자기 창을 센다. WebSocket 세션은 한 Pod 에 고정이라 세션 하나는 정확히 3초 창을 보고, 가장
 * 느슨한 경우도 Pod 수에 비례해 유계다(무제한이 아니다). 장애가 끝나면 Redis 창이 다시 권위를 갖는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisRateLimiter implements RateLimiter {

    private static final Duration WINDOW = Duration.ofSeconds(3);

    /**
     * Redis 왕복 상한 — <b>느린 Redis 를 죽은 Redis 로 수렴시킨다.</b>
     *
     * <p>이게 없으면 강등이 <b>에러 신호에만</b> 걸린다. Redis 를 죽이는 대신 패킷을 떨구면
     * (파티션·과부하) 커맨드가 Lettuce 기본 타임아웃까지 pending 인데 그 값이 <b>60초</b>이고
     * ({@code RedisURI.DEFAULT_TIMEOUT = 60l}, lettuce-core 6.8.2 바이트코드 실측)
     * {@code spring.data.redis.timeout} 은 이 저장소에 설정돼 있지 않다. 그 60초 동안
     * {@code onErrorResume} 이 돌지 않으므로 아래 로컬 창은 발화하지 않고, 전송 경로가
     * {@code concatMap} 으로 직렬화돼 있어 <b>그 세션의 채팅이 통째로 멈춘다</b> — 공격자가 얻는 것이
     * 상한 우회가 아니라 가용성 박탈이고, 그건 이 어댑터가 내세운 "가용성 우선"의 반대 방향이다.
     *
     * <p>값은 창의 1/15 다. 정상 왕복은 ms 아래라 평소 지터·GC 로는 걸리지 않고, 걸렸을 때 잃는 것은
     * 그 한 건의 Pod 간 정확도뿐이다(= 강등과 같은 대가). 거부 경로의 TTL 조회까지 함께 덮도록
     * 체인 전체에 건다.
     *
     * <p>⚠️ <b>형제 조회들은 아직 상한이 없다.</b> 전송 경로의 강퇴·밴 조회와 입장 게이트의 종료 마커
     * 조회가 같은 모양이고 같은 {@code concatMap} 위에 있어, 느린 Redis 는 그쪽에서 먼저 멈춘다.
     * 즉 "느린 Redis 가 세션을 멈추지 않는다"가 전 경로에서 참이 되려면 그쪽도 함께 유계여야 한다.
     * 이 어댑터만으로는 그 주장을 할 수 없다.
     */
    private static final Duration REDIS_CALL_LIMIT = Duration.ofMillis(200);

    /**
     * 강등 로그 간격. <b>건수가 아니라 경과시간</b>으로 솎아낸다 — 건수 기준(N건당 1줄)은 카운터가
     * 프로세스 생애 누적이라 두 번째 이후의 짧은 장애가 통째로 침묵한다(예: 누적 251~290이면 한 줄도 안 남음).
     * 규모가 작을수록 안 남는 역방향이라, 진단이 정작 어려운 산발 장애를 놓친다.
     */
    private static final long DEGRADED_LOG_INTERVAL_NANOS = Duration.ofSeconds(10).toNanos();

    private final ReactiveStringRedisTemplate redisTemplate;

    /** 강등 누적 횟수(= Redis 가 답하지 못해 로컬 창이 판정한 횟수). 로그를 솎아내도 규모는 남기기 위한 카운터. */
    private final AtomicLong degradedCount = new AtomicLong();

    /** 마지막으로 강등을 로깅한 시각. 첫 발생이 반드시 남도록 간격만큼 과거로 초기화한다. */
    private final AtomicLong lastDegradedLogNanos = new AtomicLong(System.nanoTime() - DEGRADED_LOG_INTERVAL_NANOS);

    /**
     * Redis 장애 구간의 로컬 창 — userId → 창이 끝나는 {@code nanoTime}.
     *
     * <p>항목 수는 "이 Pod 에 붙어 장애 구간에 전송한 사용자" 수를 넘지 않는다. userId 는 인증된 값이라
     * 임의로 늘릴 수 없고, 그 상한은 세션 맵과 같은 것이 민다.
     */
    private final ConcurrentHashMap<UUID, Long> localWindowEndNanos = new ConcurrentHashMap<>();

    /** 마지막으로 만료된 로컬 창을 쓸어낸 시각. */
    private final AtomicLong lastSweepNanos = new AtomicLong(System.nanoTime());

    @Override
    public Mono<RateLimitResult> tryAcquire(UUID userId) {
        String key = ChatRedisKeys.rateLimit(userId);
        return redisTemplate.opsForValue()
                .setIfAbsent(key, "1", WINDOW)
                .flatMap(acquired -> acquired
                        ? Mono.just(new RateLimitResult(true, 0))
                        : remainingSeconds(key).map(remaining -> new RateLimitResult(false, remaining)))
                .timeout(REDIS_CALL_LIMIT)
                .onErrorResume(err -> {
                    // Redis가 죽으면 전 사용자의 모든 전송이 이 경로를 탄다. 건당 스택트레이스를 남기면
                    // 초당 수백 줄이 쌓여 정작 원인(Redis 장애) 로그를 묻는다. 그래서 간격으로 솎아낸다.
                    // userId는 뺀다: SET NX는 키별 오류 경로가 없어 여기 오는 건 사실상 전역 장애이고,
                    // 표본 하나에 찍힌 id는 "이 사용자 문제"로 오독을 부른다. 대신 예외를 그대로 실어
                    // 위치를 남긴다 — 이 catch는 전 예외를 받으므로 체인 내부 결함이 흘러들 수도 있는데,
                    // 그때는 스택트레이스가 id보다 훨씬 쓸모 있다.
                    long count = degradedCount.incrementAndGet();
                    if (shouldLogDegraded()) {
                        log.error("rate limit Redis 장애 — 로컬 창으로 강등(누적 {}건)", count, err);
                    }
                    return Mono.just(localWindow(userId, System.nanoTime()));
                });
    }

    /** 마지막 로그로부터 간격이 지났으면 true. 경쟁 시 한 스레드만 통과한다(스로틀이 곧 중복 억제). */
    private boolean shouldLogDegraded() {
        long now = System.nanoTime();
        long last = lastDegradedLogNanos.get();
        return now - last >= DEGRADED_LOG_INTERVAL_NANOS && lastDegradedLogNanos.compareAndSet(last, now);
    }

    /**
     * Redis 가 답하지 못할 때의 판정 — 같은 3초 고정 창을 이 Pod 안에서 적용한다.
     *
     * <p>{@code compute}는 키 단위로 원자적이다. 같은 사용자의 탭 둘이 이 Pod 에 붙어 동시에 들어와도
     * 둘 다 통과하지 않는다 — Redis 경로가 {@code SET NX} 로 얻던 성질을 여기서도 지킨다. 읽고 나서
     * 쓰는 모양으로 적으면 그 사이에 둘 다 "창이 비었다"를 보고 통과한다.
     *
     * <p>시각을 인자로 받는다(테스트 진입점) — 창이 지나 다시 허용되는지, 지난 항목이 실제로 쓸려 나가는지를
     * 재려면 3초를 기다려야 하는데, 그 기다림이 테스트에 들어가면 느려서 아무도 안 돌리거나 경계에서
     * 흔들린다. 운영 호출부는 {@code System.nanoTime()} 하나뿐이다.
     */
    RateLimitResult localWindow(UUID userId, long now) {
        sweepExpiredLocalWindows(now);
        // 람다 안에서 판정 결과를 돌려줄 자리가 없어 1칸 배열에 담는다 — 창이 열려 있었으면 남은 나노초,
        // 비어 있었으면 0 이 그대로 남는다(0 이 곧 "허용"이다).
        long[] remainingNanos = new long[1];
        localWindowEndNanos.compute(userId, (key, windowEnd) -> {
            if (windowEnd != null && windowEnd - now > 0) {
                remainingNanos[0] = windowEnd - now;
                // 연장하지 않는다 — 거부가 창을 밀면 계속 두드리는 클라가 스스로 영구 차단된다.
                // Redis 경로도 거부 시 잔여 TTL 만 읽고 윈도우를 건드리지 않는다(같은 fixed window 계약).
                return windowEnd;
            }
            return now + WINDOW.toNanos();
        });
        if (remainingNanos[0] == 0) {
            return new RateLimitResult(true, 0);
        }
        // 올림 + 최소 1. <b>Redis 경로와 같은 계약이 아니다</b> — 한때 그렇게 적었는데 거짓이다.
        // 이쪽은 올리고 저쪽({@code remainingSeconds})은 {@code ttl.toSeconds()} 가 0 쪽으로 버려
        // 내린다. 남은 2.5초가 여기서는 3, 저기서는 2 다. 공유되는 것은 {@code max(1, …)} 뿐이다.
        // 올리는 쪽이 더 안전하다 — 내리면 창이 남았는데 짧게 알려 줘서 클라가 일찍 재시도하고
        // 거부 왕복이 한 번 더 붙는다. 그러니 이 문장을 근거로 양쪽을 "맞추지" 말 것.
        return new RateLimitResult(false,
                Math.max(1, (remainingNanos[0] + 999_999_999L) / 1_000_000_000L));
    }

    /**
     * 만료된 로컬 창을 쓸어낸다 — 창 길이마다 한 번만.
     *
     * <p>항목은 창이 지나면 쓸모가 없는데 스스로 사라지지 않는다. 호출마다 쓸면 O(n) 이 프레임마다 붙어
     * <b>정작 막으려는 과부하 구간에서 가장 비싸진다.</b> 그래서 간격으로 솎는다 — 위 로그 스로틀과 같은
     * 방식이고, 경쟁 시 한 스레드만 통과하는 것도 같다.
     */
    private void sweepExpiredLocalWindows(long now) {
        long last = lastSweepNanos.get();
        if (now - last < WINDOW.toNanos() || !lastSweepNanos.compareAndSet(last, now)) {
            return;
        }
        localWindowEndNanos.values().removeIf(windowEnd -> windowEnd - now <= 0);
    }

    /** 추적 중인 로컬 창 수 (테스트 진입점) — 장애가 끝난 뒤 항목이 실제로 풀리는지 재는 데 쓴다. */
    int trackedLocalWindows() {
        return localWindowEndNanos.size();
    }

    private Mono<Long> remainingSeconds(String key) {
        return redisTemplate.getExpire(key)
                .map(ttl -> Math.max(1, ttl.toSeconds())) // 만료 직전 0초여도 클라 카운트다운용 최소 1
                .defaultIfEmpty(1L);
    }
}
