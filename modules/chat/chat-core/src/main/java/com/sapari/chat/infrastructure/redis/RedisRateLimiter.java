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
 * 패킷을 떨구는 장애에서는 클라이언트 상한까지 기다린다) — 같은 3초 고정 창을 이 Pod 안에서만
 * 적용한다.
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
     * (파티션·과부하) 커맨드가 클라이언트 상한까지 pending 이고, 그 동안 {@code onErrorResume} 이
     * 돌지 않으므로 아래 로컬 창은 발화하지 않는다. 전송 경로가 {@code concatMap} 으로 직렬화돼
     * 있어 <b>그 세션의 채팅이 통째로 멈춘다</b> — 공격자가 얻는 것이 상한 우회가 아니라 가용성
     * 박탈이고, 그건 이 어댑터가 내세운 "가용성 우선"의 반대 방향이다.
     *
     * <p>그 클라이언트 상한은 이제 1초다({@code RedisTimeoutConfig}). 한때 이 자리에 "Lettuce 기본
     * 60초이고 {@code spring.data.redis.timeout} 이 설정돼 있지 않다" 고 적혀 있었는데, 그 설정이
     * 없던 것이 문제였고 지금은 있다 — 그래서 이 200ms 는 "60초를 막는 유일한 수단" 이 아니라
     * <b>따뜻한 경로의 더 조인 예산</b>이다.
     *
     * <p>값은 창의 1/15 다. 정상 왕복은 ms 아래라 평소 지터·GC 로는 걸리지 않고, 걸렸을 때 잃는 것은
     * 그 한 건의 Pod 간 정확도뿐이다(= 강등과 같은 대가).
     *
     * <p><b>체인 전체에 걸지 말 것.</b> 한때 이 자리에 "거부 경로의 TTL 조회까지 함께 덮도록 체인
     * 전체에 건다" 고 적혀 있었는데, 그렇게 걸면 {@code SET NX} 가 이미 false 를 줘서 판정이 끝난
     * 뒤에 TTL 조회만 느릴 때도 아래 강등으로 떨어지고, 강등의 첫 판정은 허용이다 — <b>확정된
     * 거부가 허용으로 바뀐다</b>(실측: allowed=true retryAfter=0). 판정을 내리는 호출에만 걸고,
     * 거부 뒤의 조회는 {@link #denied} 가 따로 유계로 만든다.
     *
     * <p>그래서 {@code tryAcquire} 한 건이 쓰는 예산은 이 값이 아니라 <b>그 두 배</b>다 —
     * {@code SET NX} 200ms + {@code denied()} 200ms. 전송 경로가 {@code concatMap} 으로 직렬화돼
     * 있으므로 그 값이 세션당 프레임 간격의 하한이 된다. 창의 1/15 로 잡은 예산이 거부 경로에서는
     * 실효 2/15 라는 뜻이다.
     *
     * <p>⚠️ <b>그 400ms 는 따뜻한 커넥션에서만의 경계다.</b> 공유 커넥션이 아직 수립되지 않은
     * 상태에서는 연산자 상한이 아예 무장되지 않는다 — {@code ReactiveRedisTemplate} 이 커넥션 획득을
     * {@code subscribe()} 경로에서 동기로 하고 {@code .timeout()} 은 구독이 반환된 뒤에 무장된다.
     * 그래서 그 구간은 <b>클라이언트 상한</b>이 묶는다. 블랙홀 리스너로 실측:
     *
     * <pre>
     * 따뜻한 커넥션                       210ms 내외   ← 연산자 상한이 듣는다
     * 차가운 커넥션 + 클라이언트 기본값   60,245ms     ← .timeout(200ms)·.block(1s) 둘 다 못 끊는다
     * 차가운 커넥션 + 1초 클라이언트 상한  1,115ms     ← RedisTimeoutConfig 적용 후
     * </pre>
     *
     * 조건은 좁다 — 그 Pod 의 <b>첫</b> 레이트리밋 호출이 패킷을 떨구는 Redis 를 만날 때(또는
     * {@code resetConnection} 이후)다. 그 구간의 강등이 60초에서 1초로 내려왔다.
     *
     * <p>⚠️ <b>이 상한이 고치는 것은 이 호출 하나다.</b> 증상 자체는 더 넓다 — <b>느린 Redis 가
     * {@code concatMap} 위에서 그 세션을 멈춘다.</b> 같은 모양의 호출이 전송 경로의 강퇴·밴 조회와
     * 입장 게이트의 종료 마커 조회에 있고, 느린 Redis 는 순서상 <b>그쪽에서 먼저</b> 멈춘다. 즉
     * "느린 Redis 가 세션을 멈추지 않는다"는 이 어댑터만으로는 참이 되지 않는다.
     *
     * <p><b>그렇다고 이 200ms 를 거기 복사하지 말 것.</b> 상한을 걸면 그 자리의 fail-open 방향이
     * 드러나는데 그 방향이 자리마다 다르다:
     * <ul>
     *   <li>여기: 느리면 <b>로컬 창으로 떨어진다</b> — 같은 방향의 유계 강등이다.
     *   <li>강퇴·밴 조회: 느리면 <b>밴이 없는 것으로 본다</b> — 모더레이션 판정의 fail-open 이고,
     *       {@code modules/chat/AGENTS.md} 가 그것을 의도된 결정으로 따로 다룬다.
     *   <li>종료 마커 조회: 느리면 <b>끝난 방에 입장을 허용한다</b> — 또 다른 방향이다.
     *   <li>시청자 수 조회({@code ChatSessionRedisRepository.count}): 느리면 <b>시청자 수 없이
     *       입장시킨다</b> — 이미 fail-open 으로 구현돼 있다. 연산자 상한이 없어 클라이언트 상한
     *       (1초)까지 기다리고, 거기에 비행 병합이 붙어 그 방 신규 입장자가 <b>같은</b> 멈춘 왕복에
     *       묶인다(늦게 온 호출자는 남은 시간만 기다리니 평균은 유리하지만, 그 커넥션만 멈춘
     *       경우에는 새 호출이면 즉시 성공했을 것을 전원이 함께 기다린다).
     * </ul>
     * 네 자리의 적정 값과 정책은 각각 따로 근거를 가져야 한다. 유추로 확장할 성질이 아니다.
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
     * 거부 뒤 TTL 조회 실패 누적. <b>강등과 따로 센다</b> — 한쪽의 첫 발생을 다른 쪽이 묻지 않게
     * 하라는 정본 규율이고, 두 사건의 대응도 다르다(이쪽은 판정이 유지되므로 덜 급하다).
     *
     * <p>⚠️ <b>둘을 더해서 "열화 횟수" 로 읽지 말 것.</b> {@code degradedCount} 는 "판정을 Redis 가
     * 못 내려 로컬 창으로 내려갔다"이고 이쪽은 "판정은 내렸는데 남은 시간을 못 읽었다"다. 전자는
     * Pod 간 정확도를 잃고 후자는 잃지 않는다 — 대응이 달라서 따로 센다.
     */
    private final AtomicLong deniedTtlFallbackCount = new AtomicLong();

    /** 그 로그의 마지막 시각. 첫 발생이 반드시 남도록 간격만큼 과거로 초기화한다. */
    private final AtomicLong lastDeniedTtlLogNanos =
            new AtomicLong(System.nanoTime() - DEGRADED_LOG_INTERVAL_NANOS);

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
                // 상한은 <b>판정을 내리는 호출에만</b> 걸린다. 체인 전체에 걸면 거부가 확정된 뒤의
                // TTL 조회가 느릴 때도 아래 강등으로 떨어지고, 강등의 첫 판정은 허용이다 —
                // <b>확정된 거부가 허용으로 바뀐다</b>(실측: SET NX=false + 느린 TTL →
                // allowed=true retryAfter=0). 거부 뒤의 조회는 denied() 가 따로 유계로 만든다.
                .timeout(REDIS_CALL_LIMIT)
                .flatMap(acquired -> acquired
                        ? Mono.just(new RateLimitResult(true, 0))
                        : denied(key))
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

    /**
     * 마지막 로그로부터 간격이 지났으면 true. 경쟁 시 한 스레드만 통과한다(스로틀이 곧 중복 억제).
     *
     * <p>CAS 에 지는 쪽은 조용히 넘어간다 — 그래도 <b>카운터는 이미 올라가 있어</b> 규모는 남는다.
     * 솎기가 지우는 것은 줄 수지 사건 수가 아니다.
     *
     * <p>{@code System.nanoTime()} 을 직접 쓴다. ArchUnit 의 TimeProvider 규칙이 보는 범위는
     * {@code ..application..}·{@code ..domain..} 이고 이 클래스는 infrastructure 라 밖이다. 더
     * 중요하게 <b>재는 것이 경과 시간이지 시각이 아니다</b> — {@code TimeProvider} 는 벽시계라 NTP
     * 점프에 흔들린다. 로컬 창 판정도 같은 이유로 {@code nanoTime} 이다.
     */
    private boolean shouldLogDegraded() {
        long now = System.nanoTime();
        long last = lastDegradedLogNanos.get();
        return now - last >= DEGRADED_LOG_INTERVAL_NANOS && lastDegradedLogNanos.compareAndSet(last, now);
    }

    /**
     * 거부가 확정된 뒤의 잔여 TTL 조회.
     *
     * <p><b>실패해도 허용으로 바뀌지 않는다.</b> {@code SET NX} 가 이미 false 를 줬으므로 판정은
     * 끝났고, 남은 것은 "클라에게 몇 초를 알려 줄까" 뿐이다. 그 조회가 느리거나 깨졌다고 판정을
     * 뒤집으면 창 안의 전송이 통과한다.
     *
     * <p>모르는 잔여 시간은 <b>창 전체</b>로 답한다. 실제 잔여보다 길게 말하는 쪽이라 클라가 조금
     * 더 기다리는 것이 대가인데, 짧게 말하면 클라가 일찍 재시도해 거부 왕복이 한 번 더 붙는다.
     */
    private Mono<RateLimitResult> denied(String key) {
        return remainingSeconds(key)
                .map(remaining -> new RateLimitResult(false, remaining))
                .timeout(REDIS_CALL_LIMIT)
                // ⚠️ <b>흡수하되 침묵하지 않는다.</b> 한때 여기가 onErrorReturn 한 줄이었는데, 그러면
                // 거부 경로의 Redis 열화가 카운터도 로그도 없이 사라진다 — 이 상한이 생기기 전에는
                // 같은 실패가 바깥 onErrorResume 으로 흘러 강등 카운터와 솎은 로그를 남겼으므로,
                // 판정을 지키는 대가로 관측을 잃은 셈이었다. 판정은 그대로 두고 신호만 되돌린다.
                .onErrorResume(err -> {
                    long count = deniedTtlFallbackCount.incrementAndGet();
                    if (shouldLogDeniedTtlFallback()) {
                        // 키를 싣지 않는다 — ratelimit:chat:{userId} 라 그 자체가 PII 다. 위 강등
                        // 로그와 같은 결정이고, 예외는 그대로 실어 위치를 남긴다.
                        //
                        // 그 예외가 키를 흘리지 않는지 재 봤다: 연결 실패 사슬은
                        // RedisConnectionFailureException → RedisConnectionException →
                        // AnnotatedConnectException 이고 호스트·포트만 싣는다(키·userId 둘 다 미포함).
                        // <b>측정한 것은 그 한 모양뿐이다</b> — 커맨드 타임아웃이나 서버발 오류
                        // (WRONGTYPE 등)가 명령·키를 인용하는지는 재지 않았다. 그 경로가 생기면
                        // 여기서 err 를 그대로 싣는 선택을 다시 봐야 한다.
                        log.error("거부 확정 뒤 잔여 TTL 조회 실패 — 창 전체로 답한다(누적 {}건)", count, err);
                    }
                    return Mono.just(new RateLimitResult(false, WINDOW.toSeconds()));
                });
    }

    /** 강등 로그와 같은 규율 — 간격 기반, 경쟁 시 한 스레드만 통과. */
    private boolean shouldLogDeniedTtlFallback() {
        long now = System.nanoTime();
        long last = lastDeniedTtlLogNanos.get();
        return now - last >= DEGRADED_LOG_INTERVAL_NANOS
                && lastDeniedTtlLogNanos.compareAndSet(last, now);
    }

    /** 거부 뒤 TTL 조회 실패 누적 수. (테스트 진입점 — 흡수가 침묵이 아닌지 재는 데 쓴다) */
    long deniedTtlFallbacks() {
        return deniedTtlFallbackCount.get();
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

    /**
     * 추적 중인 로컬 창 수 (테스트 진입점) — 만료 항목이 실제로 풀리는지 재는 데 쓴다.
     *
     * <p>쓸기는 강등 경로에서만 돌므로 <b>마지막 코호트는 다음 강등 호출에서</b> 풀린다. Redis 가
     * 살아난 뒤로는 그 호출이 오지 않으니 그 항목들은 Pod 수명 내내 남는다 — 유계이고 소량이다
     * (창 3초 + 다음 쓸기까지 ≤3초 분량, 키는 서명 검증된 userId 뿐).
     */
    int trackedLocalWindows() {
        return localWindowEndNanos.size();
    }

    private Mono<Long> remainingSeconds(String key) {
        return redisTemplate.getExpire(key)
                .map(ttl -> Math.max(1, ttl.toSeconds())) // 만료 직전 0초여도 클라 카운트다운용 최소 1
                .defaultIfEmpty(1L);
    }
}
