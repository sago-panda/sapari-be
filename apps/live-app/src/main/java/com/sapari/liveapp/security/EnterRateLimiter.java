package com.sapari.liveapp.security;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import lombok.extern.slf4j.Slf4j;

import com.sapari.live.application.port.EnterPopulation;
import com.sapari.live.application.port.EnterRejectionGate;
import com.sapari.live.application.port.LiveMetrics;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 입장(=룸 토큰 발급) 상한. 게스트·회원·판매자가 각자 통로를 갖는다.
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
 * <p><b>판매자는 전용 통로를 쓴다</b>({@link #trySellerEntry}, SPR-185). 판매자가 회원 샤드·전역을
 * 같이 쓰던 때는 시청자가 몰리면 재접속이 거부됐고, 그러면 {@code owner} 클레임이 실린 룸 토큰이
 * 발급되지 않아 진행 중 방송의 판매자가 chat 의 강퇴·비마스킹 원문 권한을 복구할 수 없었다.
 *
 * <p>⚠️ <b>판매자끼리는 같은 샤드 안에서 서로 밀어낼 수 있다.</b> 통로가 버킷 하나였다면 판매자
 * 계정 하나가 초당 몇 건으로 <b>모든</b> 판매자를 막았다 — 그래서 회원과 같은 키 혼합 샤드로 나눴고,
 * 피해는 공격자와 같은 샤드의 판매자로 묶인다. 다계정(약 22개면 전 샤드)의 한계와 그것을 받아들인
 * 근거(사업자 검증)는 {@link EnterRateLimitProperties} 의 판매자 기본값 주석에 있다. 남는 구멍 하나:
 * 이 필터는 폐기 조회({@code TokenRevocationFilter})보다 앞이라, 폐기된 판매자 토큰도 만료까지 자기
 * 샤드를 소모한다. 기존 WS 세션은 유지되므로 영향은 (재)접속 순간에 한정된다.
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

    /**
     * 샤드 버스트에 주는 배수 — <b>정상 재접속 폭풍을 거부 없이 흡수하는 데 필요한 여유</b>다.
     *
     * <p>샤드로 쪼개면 버스트 여유가 샤드 간에 <b>풀링되지 않는다.</b> 1,000명이 동시에
     * 재입장하면 샤드별 도착 수가 이항분포를 따르고(기본 64샤드에서 평균 15.6 · σ 3.9)
     * 샤드 버스트가 그 평균과 같으면 절반쯤의 샤드가 넘친다 — 실측으로 <b>12.0%(평균
     * 120.2건)가 거부</b>됐다. 전역 버스트 1,000 은 남아 있는데도 그렇다.
     *
     * <p>2 는 평균 + 약 4σ 에 해당하고 그 지점에서 같은 폭풍의 거부가 <b>0.0%</b> 였다
     * (측정: 1,000명 균일 분배 3,000회 시행 · 3 으로 올려도 0.0% 로 더 얻는 것이 없다).
     *
     * <p>⚠️ 대가는 한 계정의 <b>일회분</b>이 커지는 것이다 — 기본값에서 15 → 32(전역 버스트
     * 1,000 의 3.2%). 지속분은 샤드 리필이 잡으므로(기본 8/s) 계정 하나로 전역을 <b>계속</b>
     * 비워 두지는 못한다. 사람을 막는 것은 지속분이고, 일회분 32 는 다음 초에 회복된다.
     */
    private static final int BURST_HEADROOM = 2;

    /**
     * 샤드 선택 <b>키</b> — 프로세스 로컬 랜덤. 샤드를 <b>조준 불가</b>로 만든다.
     *
     * <p>막으려는 것: 공격자가 대상의 샤드를 <b>오프라인에서</b> 계산하는 것. 샤드 키가
     * {@code sub}(=userId)이고 미인증으로 열린 목록 응답이 방송 중 판매자의 userId 를 그대로
     * 준다(실측으로 확인한 경로: {@code LiveSecurityConfig} 의 {@code GET /api/v1/lives/rooms}
     * → {@code permitAll} → {@code LiveController.getRooms} → {@code GetLiveView.rooms} →
     * {@code LiveRoomSummary.sellerId}). 그 샤드에 떨어지는 계정이 나올 때까지 가입만 하면
     * (가입 스로틀 없음) 계정 하나로 그 샤드를 상시 비울 수 있고, 그러면 판매자가 재입장으로
     * {@code owner} 클레임을 복구할 수 없다 — chat 의 강퇴·비마스킹 원문 게이트가 그 클레임이다.
     *
     * <p>⚠️ <b>한때 이것이 문자열 솔트를 주체 앞에 <i>이어 붙이는</i> 방식이었고, 그 방식은
     * 위 공격을 막지 못했다(SPR-179 에서 실측).</b> 이유는 대수다 —
     * {@code hash(a+b) = hash(a)·31^|b| + hash(b)} 이고 UUID 문자열 길이 36 은 짝수라
     * {@code 31^36 ≡ 1 (mod 64)}, 그래서 솔트는 샤드 인덱스에 <b>상수 회전</b>으로만 들어갔다.
     * 공격자에게 필요한 것은 절대 샤드 번호가 아니라 "내 계정이 타깃과 같은 샤드인가" 이고
     * 그 <b>관계</b>에서는 회전이 양쪽에 똑같이 더해져 소거된다. 실측: 솔트를 모른 채
     * {@code salt=""} 로 고른 후보가 실제 솔트에서도 타깃과 같은 샤드인 비율이
     * <b>2000/2000 = 100%</b>(대조군 1.25% · 우연 기대 1.5625%)였다.
     *
     * <p>그래서 이어 붙이지 않고 <b>키로 쓴다</b>(HmacSHA256). MAC 은 키 없이 충돌 관계를
     * 계산할 수 없으므로 위 소거가 성립하지 않는다. 비용은 <b>서브-µs</b> 다 — 연접이 수십 ns,
     * HMAC(스레드 로컬 재사용)이 그 두어 배다. ⚠️ 세 자리로 적지 않는다: 독립 측정 둘이
     * 연접에서 4배까지 갈렸고(기계·JIT 차) 결론만 같다. 이 경로는 통과 시 Redis 왕복 3회(≈3ms)를
     * 쓰므로 어느 쪽이든 그 1만분의 1 수준이다 — 자릿수를 여행시키면 다음 사람이 그 차이를
     * 결함으로 읽는다.
     *
     * <p>남는 위험은 조준이 아니라 <b>살포</b>다 — 샤드 전부를 덮으려면 계정이 많이 필요하다
     * (아래 {@code shardFor} javadoc의 쿠폰 수집 수치).
     */
    private final byte[] shardKey = newShardKey();

    /**
     * {@code Mac} 은 상태를 들고 있어 스레드 안전하지 않다 — 스레드마다 하나 둔다.
     *
     * <p>{@code ThreadLocal} 누수를 걱정하지 않는 이유는 호출자가 서블릿 스레드 풀(유계)이기
     * 때문이다. 호출마다 새로 만드는 쪽도 0.412µs 로 충분히 싸지만, 3배 차이를 공짜로 버릴
     * 이유가 없다.
     */
    private final ThreadLocal<Mac> shardMac = ThreadLocal.withInitial(this::newShardMac);

    private final EnterRateLimitProperties properties;
    private final LiveMetrics metrics;
    private final TokenBucket guestBucket;
    private final TokenBucket memberBucket;
    /** 판매자 통로의 전역 버킷 — 회원 샤드·전역과 분리돼 있다({@link #trySellerEntry}). */
    private final TokenBucket sellerBucket;
    /** 판매자 샤드 — 회원 샤드와 같은 키({@link #shardKey})로 고른다. */
    private final TokenBucket[] sellerShards;
    /**
     * 회원 샤드 버킷 — {@code sub} 해시로 고른다. 길이가 고정이라 계정을 양산해도 늘어나는
     * 것이 없다({@link EnterRateLimitProperties} javadoc).
     */
    private final TokenBucket[] memberShards;
    private final AtomicLong guestRejected = new AtomicLong();
    private final AtomicLong memberRejected = new AtomicLong();
    private final AtomicLong sellerRejected = new AtomicLong();
    /**
     * 마지막으로 로그를 남긴 시각 — <b>집단마다 따로다.</b> 벽시계가 아니라 단조 시계다
     * ({@link TokenBucket} 과 같은 이유).
     *
     * <p>하나로 합치면 게스트 폭주가 창을 선점해 "로그인한 실사용자가 거부되고 있다" 가 안
     * 뜬다. {@code EnterRateLimitFilterTest} 가 그 회귀를 잡는다.
     */
    private final AtomicLong guestLastLogNanos;
    private final AtomicLong memberLastLogNanos;
    private final AtomicLong sellerLastLogNanos;
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
        this.sellerBucket = new TokenBucket(
                properties.sellerBurst(), properties.sellerPermitsPerSecond(), nanoClock);
        this.sellerShards = newShards(properties.sellerShards(),
                properties.sellerPermitsPerSecond(), properties.sellerBurst(), nanoClock);
        int shards = properties.memberShards();
        this.memberShards = new TokenBucket[shards];
        // 샤드 몫을 <올림>으로 내고 버스트에 헤드룸을 준다. 합이 전역을 넘으므로 집계 천장은
        // 전역 버킷이 집행하고(그래서 설정에 적은 수가 실제 천장이다) 샤드는 <한 계정>의
        // 몫만 묶는다. 바닥 나눗셈이면 합이 전역보다 작아 설정값이 집행되지 않는다 —
        // 기본값에서 지속 448/s · 버스트 960(설정은 500/1,000)이었다.
        int shardBurst = ceilDiv(properties.memberBurst(), shards) * BURST_HEADROOM;
        int shardRefill = ceilDiv(properties.memberPermitsPerSecond(), shards);
        for (int i = 0; i < shards; i++) {
            this.memberShards[i] = new TokenBucket(shardBurst, shardRefill, nanoClock);
        }
        this.guestLastLogNanos = new AtomicLong(nanoClock.getAsLong() - LOG_INTERVAL_NANOS);
        this.memberLastLogNanos = new AtomicLong(nanoClock.getAsLong() - LOG_INTERVAL_NANOS);
        this.sellerLastLogNanos = new AtomicLong(nanoClock.getAsLong() - LOG_INTERVAL_NANOS);
    }

    /** 미인증 입장 한 건. {@code false} 면 호출자가 429 로 돌려보낸다. */
    public boolean tryGuestEntry() {
        if (guestBucket.tryAcquire()) {
            return true;
        }
        // 지표가 먼저다 — 로그는 집단당 10초 1줄로 솎이므로 규모를 못 주고, 규모가 바로
        // "공격인가 한도가 낮은가" 를 가르는 축이다. 어댑터는 던지지 않는다(포트 계약).
        // 게스트는 샤드가 없어 관문이 항상 전역이다.
        metrics.enterRateLimited(EnterPopulation.GUEST, EnterRejectionGate.GLOBAL);
        logThrottled("게스트", EnterRejectionGate.GLOBAL, guestLastLogNanos,
                guestRejected.incrementAndGet(),
                properties.guestPermitsPerSecond(), properties.guestBurst());
        return false;
    }

    /**
     * 인증 입장 한 건. 게스트 버킷과 분리돼 있어 미인증 폭주가 이쪽을 굶기지 않는다.
     *
     * <p><b>두 관문을 지나고 역할이 다르다.</b> 샤드 버킷은 <b>한 계정</b>의 몫을 묶고,
     * 전역 버킷이 <b>집계 천장</b>을 집행한다 — 샤드 용량의 합이 전역을 넘도록 올려 두었으니
     * (생성자 참조) 둘 중 구속하는 쪽은 전역이고, 그래서 설정에 적은 수가 실제 천장이다.
     *
     * <p>순서는 <b>샤드가 먼저</b>다. 전역을 먼저 두면 <b>샤드에서 떨어질 요청까지 전역
     * 토큰을 소모</b>하므로 한 계정이 자기 몫을 넘어 전역을 비울 수 있다. 샤드를 먼저 두면
     * 그 요청은 전역을 건드리지 않는다.
     *
     * <p>⚠️ 거울상이 하나 있다 — <b>전역에서 떨어지는 요청은 샤드 토큰을 이미 썼다</b>
     * (샤드 소모가 전역 검사보다 앞이다). 집계 포화 시 회원이 자기 샤드도 같이 소모하므로
     * 회복이 그만큼 늦다. 순서를 바꿔 닫히는 것이 아니고(위 단락이 그 대가다) 전역 포화는
     * 지속분이 전역 리필을 넘을 때만 생긴다.
     *
     * @param subject 검증된 JWT 의 주체. {@code null} 이면 샤드를 건너뛴다 — 신원 없는 인증
     *                요청은 없어야 하지만({@code EnterRateLimitFilter} 는
     *                {@code member.userId().toString()} 이라 {@code null} 이면 거기서 NPE 다)
     *                있다면 전역 천장은 그대로 적용돼야 한다.
     */
    public boolean tryMemberEntry(String subject) {
        if (subject != null && !shardFor(subject).tryAcquire()) {
            // 같은 지표를 쓰되 관문을 가른다 — 이 거부는 "그 계정을 찾아라" 이고, 아래
            // 전역 거부는 "한도를 올려라" 다. 합치면 당직자가 반대 대응을 고를 수 있다.
            metrics.enterRateLimited(EnterPopulation.MEMBER, EnterRejectionGate.SHARD);
            logThrottled("회원", EnterRejectionGate.SHARD, memberLastLogNanos,
                    memberRejected.incrementAndGet(),
                    properties.memberPermitsPerSecond(), properties.memberBurst());
            return false;
        }
        if (memberBucket.tryAcquire()) {
            return true;
        }
        metrics.enterRateLimited(EnterPopulation.MEMBER, EnterRejectionGate.GLOBAL);
        logThrottled("회원", EnterRejectionGate.GLOBAL, memberLastLogNanos,
                memberRejected.incrementAndGet(),
                properties.memberPermitsPerSecond(), properties.memberBurst());
        return false;
    }

    /**
     * 판매자 입장 한 건 — 회원 샤드·전역을 <b>건너뛰고</b> 판매자 샤드 → 판매자 전역을 지난다.
     *
     * <p>판매자가 회원 통로를 같이 쓰면 시청자가 몰리는 순간 재접속이 429 가 되고, 그러면
     * {@code owner} 클레임이 실린 룸 토큰을 못 받아 chat 의 강퇴·비마스킹 권한을 잃는다.
     * 관문 순서와 그 이유는 {@link #tryMemberEntry} 와 같다(샤드가 먼저).
     *
     * @param subject 검증된 JWT 의 주체. {@code null} 이면 샤드를 건너뛴다({@link #tryMemberEntry} 와 같다).
     */
    public boolean trySellerEntry(String subject) {
        if (subject != null && !sellerShards[sellerShardIndexFor(subject)].tryAcquire()) {
            metrics.enterRateLimited(EnterPopulation.SELLER, EnterRejectionGate.SHARD);
            logThrottled("판매자", EnterRejectionGate.SHARD, sellerLastLogNanos,
                    sellerRejected.incrementAndGet(),
                    properties.sellerPermitsPerSecond(), properties.sellerBurst());
            return false;
        }
        if (sellerBucket.tryAcquire()) {
            return true;
        }
        metrics.enterRateLimited(EnterPopulation.SELLER, EnterRejectionGate.GLOBAL);
        logThrottled("판매자", EnterRejectionGate.GLOBAL, sellerLastLogNanos,
                sellerRejected.incrementAndGet(),
                properties.sellerPermitsPerSecond(), properties.sellerBurst());
        return false;
    }

    private void logThrottled(String which, EnterRejectionGate gate, AtomicLong lastLogNanos,
            long count, int permitsPerSecond, int burst) {
        if (!shouldLog(lastLogNanos)) {
            return;
        }
        // 이 줄이 뜬다는 것은 입장이 실제로 거부되고 있다는 뜻이다. 한도는 정상 트래픽에서
        // 역산한 값이 <아니므로>(properties javadoc) 공격일 수도, 한도가 낮은 것일 수도 있다.
        // 둘을 가르는 것은 같은 시각의 반대쪽 집단 수치이고, 그래서 양쪽 누적을 한 줄에 같이
        // 싣는다 — 억제는 집단별이라 두 줄이 같은 창에 뜰 수 있고 한 줄만 봐도 비율이 보인다.
        // 복구는 롤링 재시작이다(한도가 생성자 1회 조립).
        //
        // ⚠️ 누적은 <프로세스 수명> 누적이다(리셋 없음) — "이 창의 건수" 가 아니다.
        //
        // ⚠️ 관문은 <이 줄이 어디서 났는지>만 말한다. 억제가 집단별이라 한 창에 두 관문이
        // 모두 거부해도 먼저 찍힌 쪽 한 줄만 뜬다 — 관문별 비율은 로그가 아니라
        // live.enter.rejected 의 gate 태그에서 본다.
        log.warn("{} 입장 상한 초과({} 관문) — 공격이거나 한도가 낮다. 한도={}/s(burst {}), "
                        + "프로세스 누적 거부: 이 집단={}건 게스트={}건 회원={}건 판매자={}건",
                which, gate, permitsPerSecond, burst, count,
                guestRejected.get(), memberRejected.get(), sellerRejected.get());
    }

    /**
     * 주체를 샤드에 붙인다 — {@link #shardKey} 로 키 혼합(MAC)해 조준을 막는다.
     *
     * <p>{@code floorMod} 를 쓰는 이유: {@code hashCode()} 는 음수일 수 있고 {@code %} 는 그
     * 부호를 보존해 배열 인덱스가 음수가 된다.
     *
     * <p>⚠️ <b>남는 위험은 살포다.</b> 솔트는 어느 계정이 어느 샤드인지를 가리지만 계정을
     * 여러 개 만들어 샤드를 넓게 덮는 것은 막지 않는다. 쿠폰 수집 문제라 비용이 선형이
     * 아니다 — 64샤드를 <b>전부</b> 덮는 데 평균 <b>301개</b>(측정: 균일 분배 3,000회 시행)가
     * 들고, 64개로는 기대 커버리지가 63.5% 다.
     */
    private TokenBucket shardFor(String subject) {
        return memberShards[shardIndexFor(subject)];
    }

    /**
     * 배치를 테스트에서 직접 볼 수 있게 낸 이음 — 조준 불가는 <b>인덱스</b>에 대한 성질이라
     * 통과/거부로 간접 관측하면 재는 축이 흐려진다(처음에 그렇게 썼다가 지문이 샤드가 아니라
     * 샤드 용량이었다).
     */
    int shardIndexFor(String subject) {
        return Math.floorMod(foldedMac(subject), memberShards.length);
    }

    /** 판매자 샤드 인덱스 — 회원과 같은 키를 쓴다. 테스트가 배치를 이 메서드로 묻는다. */
    int sellerShardIndexFor(String subject) {
        return Math.floorMod(foldedMac(subject), sellerShards.length);
    }

    private int foldedMac(String subject) {
        byte[] digest = shardMac.get()
                .doFinal(subject.getBytes(StandardCharsets.UTF_8));
        // 앞 4바이트를 int 로 접는다. MAC 출력은 균등하므로 어느 4바이트를 쓰든 같다 —
        // floorMod 로 음수를 접는 것은 이 int 가 부호 있는 값이기 때문이다.
        return ((digest[0] & 0xff) << 24) | ((digest[1] & 0xff) << 16)
                | ((digest[2] & 0xff) << 8) | (digest[3] & 0xff);
    }

    private static byte[] newShardKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    /**
     * 스레드 하나를 위한 MAC.
     *
     * <p>{@code HmacSHA256} 이 없는 JRE 는 없다(JCA 필수 알고리즘)지만, 없으면 <b>던진다</b> —
     * 조용히 연접으로 떨어지면 위 javadoc 이 적은 공격이 그대로 돌아오고, 그 사실이 어디에도
     * 안 남는다. 샤드 배치는 상한의 일부이므로 "관측을 잃는" 것이 아니라 "집행을 잃는" 쪽이다.
     */
    private Mac newShardMac() {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(shardKey, "HmacSHA256"));
            return mac;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(
                    "HmacSHA256 을 쓸 수 없다 — 샤드 조준 방어가 성립하지 않으므로 기동을 막는다", e);
        }
    }

    /** 회원 샤드와 같은 몫 계산(올림 + 버스트 헤드룸) — 근거는 생성자의 회원 샤드 주석. */
    private static TokenBucket[] newShards(int shards, int permitsPerSecond, int burst,
            LongSupplier nanoClock) {
        TokenBucket[] buckets = new TokenBucket[shards];
        int shardBurst = ceilDiv(burst, shards) * BURST_HEADROOM;
        int shardRefill = ceilDiv(permitsPerSecond, shards);
        for (int i = 0; i < shards; i++) {
            buckets[i] = new TokenBucket(shardBurst, shardRefill, nanoClock);
        }
        return buckets;
    }

    /** 올림 나눗셈. 샤드 몫이 전역을 <b>넘도록</b> 올려야 집계 천장이 전역에서 집행된다. */
    private static int ceilDiv(int dividend, int divisor) {
        return -Math.floorDiv(-dividend, divisor);
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
