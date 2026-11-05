package com.sapari.liveapp.security;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import lombok.extern.slf4j.Slf4j;

import com.sapari.live.application.port.EnterPopulation;
import com.sapari.live.application.port.LiveMetrics;

/**
 * 입장(=룸 토큰 발급) 상한. 게스트와 회원이 각자 버킷을 갖는다.
 *
 * <p><b>왜 상한이 필요한가.</b> {@code GET /api/v1/lives/rooms/{roomId}} 는 미인증도 받고
 * 호출마다 새 룸 토큰을 찍는다. 그 한 건이 치르는 것은 둘이다 — {@code findById}(방송을
 * 시작하는 그 커넥션 풀) 과 <b>RS256 서명</b>(실측 685µs = 코어당 1,458건/s). 상한이 없으면
 * 미인증 호출자도, <b>가입 1회로 토큰을 얻은 인증 호출자도</b> 그 둘을 무제한으로 소모한다.
 *
 * <p>⚠️ 한때 이 비용을 "형제 webhook 의 서명 검증 31µs 보다 22배" 로 적었다. <b>그 비교는
 * 성립하지 않는다</b> — webhook 경로는 RSA 검증을 하지 않는다(LiveKit API secret 이 대칭키라
 * 본문 해싱 + 대칭 JWT 검증이고, 형제 javadoc 의 실측값이 본문 크기에 비례하는 것이 그
 * 지문이다: 512B 4.5µs → 64KB 425µs. RSA 검증은 고정비용이다). 즉 31µs 는 <b>이 시스템이
 * 치르지 않는 연산</b>의 수였다. 비교를 지우고 이 경로의 실측값만 남긴다.
 *
 * <p>축을 왜 이렇게 갈랐는지(집단 둘 · 회원은 샤드 배열 · IP 아님 · 무한 맵 아님)는
 * {@link EnterRateLimitProperties} javadoc 이 적는다.
 *
 * <p>⚠️ <b>방 주인에게 예약 용량이 없다.</b> 샤드가 한 계정의 피해 반경을 묶지만 판매자 자신의
 * 샤드를 비우는 것은 막지 않는다 — 같은 샤드에 해싱된 회원들이나 자기 재시도만으로도 비워진다.
 * 거부되면 입장이 도달하지 않고 {@code owner} 클레임이 실린 룸 토큰도 발급되지 않는데, 그
 * 클레임이 chat 의 강퇴·비마스킹 원문 게이트다. 즉 진행 중 방송의 판매자가 <b>재접속으로</b>
 * 조정 권한을 복구할 수 없다. 기존 WS 세션은 유지되므로 영향은 (재)접속 순간에 한정된다.
 *
 * <p><b>던지지 않고 {@code false} 를 돌려준다.</b> 던지면 공용
 * {@code GlobalExceptionHandler} 가 <b>거부마다 WARN 한 줄</b>을 쓴다(그 핸들러는 4xx 를
 * {@code log.warn} 으로 남긴다). 실측: {@code 1/s} 버킷에 51회 호출 → 거부 50건에 핸들러
 * WARN 50줄. 아래 억제는 <b>이 클래스의 로거에만</b> 걸리므로, 던지는 모양에서는 폭주 규모에
 * 로그가 그대로 비례해 CPU 대신 로그 I/O 가 포화되고 그 사이 다른 WARN·ERROR 이 묻힌다.
 * 형제 {@code WebhookRateLimitFilter} 에 이 면이 없는 것은 그쪽이 필터에서 응답을 직접
 * 세워 예외를 만들지 않기 때문이다 — 예외 기반으로 바꾸면서 같이 들어온 비대칭이다.
 *
 * <p>시계는 {@link TokenBucket} 이 단조 시계로 잰다 — 그쪽 javadoc 이 근거를 적는다.
 */
@Slf4j
public class EnterRateLimiter {

    /**
     * 로그 억제 간격. <b>건수가 아니라 시간</b> 기준이다 — "N 건마다 한 줄" 은 비율이라 로그
     * 볼륨이 공격 규모에 비례한다. 시간 기준이면 상대가 얼마나 퍼붓든 이 간격당 한 줄이고,
     * 그 사이 건수는 누적값으로 한 번에 실어 정보는 잃지 않는다.
     */
    private static final long LOG_INTERVAL_NANOS = 10_000_000_000L; // 10s

    private final EnterRateLimitProperties properties;
    private final LiveMetrics metrics;
    private final TokenBucket guestBucket;
    private final TokenBucket memberBucket;
    /**
     * 회원 샤드 버킷 — {@code sub} 해시로 고른다. 길이가 고정이라 계정을 양산해도 늘어나는
     * 것이 없다({@link EnterRateLimitProperties} javadoc).
     */
    private final TokenBucket[] memberShards;
    private final AtomicLong guestRejected = new AtomicLong();
    private final AtomicLong memberRejected = new AtomicLong();
    /**
     * 마지막으로 로그를 남긴 시각 — <b>집단마다 따로다.</b> 벽시계가 아니라 단조 시계다
     * ({@link TokenBucket} 과 같은 이유).
     *
     * <p>⚠️ 한때 게이트가 하나였다. 그러면 게스트 폭주가 지속되는 상황(= 이 상한이 존재하는
     * 이유인 바로 그 상황)에서 창을 게스트 거부가 선점해 <b>"로그인한 실사용자가 거부되고
     * 있다" 가 안 뜬다</b> — 실측: 6창 × (게스트 100 + 회원 100) 거부에서 회원 줄 <b>0/600</b>
     * 이고 회원 누적값도 끝까지 안 나갔다.
     */
    private final AtomicLong guestLastLogNanos;
    private final AtomicLong memberLastLogNanos;
    private final LongSupplier nanoClock;

    public EnterRateLimiter(EnterRateLimitProperties properties, LiveMetrics metrics) {
        this(properties, metrics, System::nanoTime);
    }

    /**
     * 시계를 주입받는 생성자 — <b>테스트 전용이다.</b>
     *
     * <p>가장 느린 버킷도 초당 1개는 차므로 테스트가 "비었다" 를 단정하는 창이 <b>1초</b>다.
     * GC·CI 부하로 그 창을 넘기면 리필이 토큰을 돌려줘 단정이 깨진다(방향은 오탐이지만 빨간
     * 플레이크다). 설정으로는 닫을 수 없어({@code permitsPerSecond} 가 양의 int) 이음을 낸다.
     * 같은 시계가 로그 억제 간격에도 쓰여 그쪽 단정도 결정론이 된다.
     */
    EnterRateLimiter(EnterRateLimitProperties properties, LiveMetrics metrics,
            LongSupplier nanoClock) {
        this.properties = properties;
        this.metrics = metrics;
        this.nanoClock = nanoClock;
        this.guestBucket = new TokenBucket(
                properties.guestBurst(), properties.guestPermitsPerSecond(), nanoClock);
        this.memberBucket = new TokenBucket(
                properties.memberBurst(), properties.memberPermitsPerSecond(), nanoClock);
        int shards = properties.memberShards();
        this.memberShards = new TokenBucket[shards];
        for (int i = 0; i < shards; i++) {
            // 샤드당 허용량은 전역을 나눈 <몫>이다. 정수 나눗셈이라 샤드 용량의 합이 전역을
            // 넘지 않고, 그 부등식이 "전역 거부 분기는 subject != null 에서 도달 불가" 의
            // 근거다(tryMemberEntry javadoc). 0 으로 절삭되는 조합은 부팅에서 막히므로
            // (properties 가 memberPermitsPerSecond >= memberShards 를 요구한다) 하한 가드를
            // 두지 않는다 — 두면 합이 전역을 넘어 그 부등식이 깨진다.
            this.memberShards[i] = new TokenBucket(
                    properties.memberBurst() / shards,
                    properties.memberPermitsPerSecond() / shards, nanoClock);
        }
        this.guestLastLogNanos = new AtomicLong(nanoClock.getAsLong() - LOG_INTERVAL_NANOS);
        this.memberLastLogNanos = new AtomicLong(nanoClock.getAsLong() - LOG_INTERVAL_NANOS);
    }

    /** 미인증 입장 한 건. {@code false} 면 호출자가 429 로 돌려보낸다. */
    public boolean tryGuestEntry() {
        if (guestBucket.tryAcquire()) {
            return true;
        }
        // 지표가 먼저다 — 로그는 집단당 10초 1줄로 솎이므로 규모를 못 주고, 규모가 바로
        // "공격인가 한도가 낮은가" 를 가르는 축이다. 어댑터는 던지지 않는다(포트 계약).
        metrics.enterRateLimited(EnterPopulation.GUEST);
        logThrottled("게스트", guestLastLogNanos, guestRejected.incrementAndGet(),
                properties.guestPermitsPerSecond(), properties.guestBurst());
        return false;
    }

    /**
     * 인증 입장 한 건. 게스트 버킷과 분리돼 있어 미인증 폭주가 이쪽을 굶기지 않는다.
     *
     * <p><b>두 관문을 지난다.</b> 샤드 버킷이 한 계정의 피해 반경을 자기 샤드로 묶고, 전역
     * 버킷이 집단 전체의 CPU 천장을 지킨다. 순서는 <b>샤드가 먼저</b>다 — 전역을 먼저 두면
     * <b>샤드에서 떨어질 요청까지 전역 토큰을 소모</b>하므로, 한 계정이 자기 샤드 용량이 아니라
     * 전역 용량만큼의 토큰을 거부당하면서 태울 수 있다.
     *
     * <p>⚠️ 샤드를 지난 뒤 전역이 거부하는 분기는 <b>모든 호출이 {@code subject != null} 인
     * 동안</b> 도달하지 않는다 — 설정 검증이 {@code burst >= permits >= shards} 를 강제하고
     * 정수 나눗셈이라 샤드 용량·리필의 합이 전역의 그것을 넘지 못하고, 전역 소모 1건마다
     * 샤드 소모 1건이 짝지어지기 때문이다.
     *
     * <p>⚠️ <b>{@code null} 호출이 섞이면 그 전제가 깨진다</b> — 그 호출은 샤드를 건너뛰고
     * 전역만 소모하므로 짝이 없고, 그만큼 전역이 샤드보다 먼저 빌 수 있다. 그래서 전역 검사를
     * {@code null} 경로 전용으로 좁히지 않는다. {@code EnterRateLimitFilter} 는 {@code null}
     * 을 만들지 않으므로({@code member.userId().toString()} — {@code userId} 가 {@code null}
     * 이면 거기서 NPE 다) 운영에서는 짝이 유지된다.
     *
     * @param subject 검증된 JWT 의 주체. {@code null} 이면 샤드를 건너뛴다 — 신원 없는 인증
     *                요청은 없어야 하지만, 있다면 전역 천장은 그대로 적용돼야 한다.
     */
    public boolean tryMemberEntry(String subject) {
        if (subject != null && !shardFor(subject).tryAcquire()) {
            // 샤드에서 막힌 것도 회원 거부다 — 같은 지표·같은 로그를 쓴다.
            metrics.enterRateLimited(EnterPopulation.MEMBER);
            logThrottled("회원", memberLastLogNanos, memberRejected.incrementAndGet(),
                    properties.memberPermitsPerSecond(), properties.memberBurst());
            return false;
        }
        if (memberBucket.tryAcquire()) {
            return true;
        }
        metrics.enterRateLimited(EnterPopulation.MEMBER);
        logThrottled("회원", memberLastLogNanos, memberRejected.incrementAndGet(),
                properties.memberPermitsPerSecond(), properties.memberBurst());
        return false;
    }

    private void logThrottled(String which, AtomicLong gate, long count,
            int permitsPerSecond, int burst) {
        if (!shouldLog(gate)) {
            return;
        }
        // 이 줄이 뜬다는 것은 입장이 실제로 거부되고 있다는 뜻이다. 한도는 정상 트래픽에서
        // 역산한 값이 <아니므로>(properties javadoc) 공격일 수도, 한도가 낮은 것일 수도 있다.
        // 둘을 가르는 것은 같은 시각의 반대쪽 집단 수치이고, 그래서 양쪽 누적을 한 줄에 같이
        // 싣는다 — 게이트가 집단별이라 두 줄이 같은 창에 뜰 수 있고 한 줄만 봐도 비율이 보인다.
        // 복구는 롤링 재시작이다(한도가 생성자 1회 조립).
        //
        // ⚠️ 누적은 <프로세스 수명> 누적이다(리셋 없음) — "이 창의 건수" 가 아니다.
        log.warn("{} 입장 상한 초과 — 공격이거나 한도가 낮다. 한도={}/s(burst {}), "
                        + "프로세스 누적 거부: 이 집단={}건 게스트={}건 회원={}건",
                which, permitsPerSecond, burst, count,
                guestRejected.get(), memberRejected.get());
    }

    /**
     * 주체를 샤드에 붙인다.
     *
     * <p>{@code floorMod} 를 쓰는 이유: {@code hashCode()} 는 음수일 수 있고 {@code %} 는 그
     * 부호를 보존해 배열 인덱스가 음수가 된다.
     */
    private TokenBucket shardFor(String subject) {
        return memberShards[Math.floorMod(subject.hashCode(), memberShards.length)];
    }

    /**
     * 429 의 {@code Retry-After} 에 실을 초 — 기준값의 근거는 {@link EnterRateLimitProperties}.
     *
     * <p><b>지터를 더한다.</b> 전원이 같은 값을 받으면 지속 과부하에서 거부된 전원이 같은
     * 시각에 돌아와 버킷을 선착순으로 비우고 나머지가 다시 429 다 — 전역 버킷이라 "1초 뒤
     * 통과" 는 보장이 아니고, 이 상한의 수요 벡터로 적은 <b>재접속 폭풍</b>이 정확히 그
     * 모양이다. 0~2초를 더해 복귀를 흩는다.
     */
    public long retryAfterSeconds() {
        return properties.retryAfter().toSeconds()
                + java.util.concurrent.ThreadLocalRandom.current().nextLong(3);
    }

    /** 간격을 선점한 스레드 하나만 참을 받는다 — CAS 라 폭주 중에도 잠금 경합이 생기지 않는다. */
    private boolean shouldLog(AtomicLong gate) {
        long now = nanoClock.getAsLong();
        long last = gate.get();
        return now - last >= LOG_INTERVAL_NANOS && gate.compareAndSet(last, now);
    }
}
