package com.sapari.liveapp.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 입장(=룸 토큰 발급) 경로의 상한. <b>게스트와 회원이 각자 버킷을 갖는다.</b>
 *
 * <p>미설정은 허용하고(기본값) 잘못된 값은 부팅에서 막는다 — {@code application*.yml} 이 추적되지
 * 않아 "설정 없음" 이 정상 상태다. 다만 0 이나 음수로 뜨면 그 집단의 입장이 전량 막힌다.
 *
 * <p><b>왜 버킷이 둘인가.</b> 하나면 둘 중 하나가 거짓이 된다. 전역 하나면 공격자가 한도를
 * 선점해 <b>로그인 회원의 입장까지</b> 막고(형제 {@code WebhookRateLimitFilter} 가 "방어가
 * 아니라 가용성 공격 도구" 로 경고한 그 모양), 게스트에만 걸면 <b>인증 쪽 CPU 가 열린 채
 * 남는다</b> — 가입이 셀프서비스고({@code ApiSecurityConfig} 의 customer 체인이
 * {@code anyRequest().permitAll()}) 이 경로의 JWT 필터는 폐기 저장소를 안 보므로
 * ({@code StatelessJwtAuthenticationFilter} javadoc) 가입 1회로 TTL(기본 3600s) 동안 무제한
 * 호출이 된다. 버킷을 가르면 두 성질이 같이 성립한다.
 *
 * <p><b>회원 쪽은 샤드로 한 번 더 가른다.</b> 회원 버킷이 집단 전역 하나면 <b>회원 한 명이
 * 회원 전체를 굶힌다</b> — 가입에 캡차·이메일 인증이 없으므로 계정 하나로 그 버킷을 상시
 * 비울 수 있다. {@code sub} 를 <b>고정 크기 샤드 배열</b>({@code memberShards})에 해싱해
 * 샤드마다 자기 버킷을 주면 한 계정이 먹는 것이 자기 샤드 하나로 묶인다.
 *
 * <p>⚠️ <b>무한 맵과 다른 점이 그 "고정" 이다.</b> {@code sub} 는 검증된 JWT 에서 오니 조작은
 * 불가하지만 가입이 셀프서비스라 <b>키마다 버킷을 만드는 맵</b>은 형제가 반박한 IP 축과 같은
 * 메모리 증식 경로가 된다. 배열은 메모리가 구조적으로 {@code memberShards} 에 묶이고 충돌은
 * 샤드 공유로 끝난다 — 계정을 양산해도 늘어나는 것이 없다.
 *
 * <p>⚠️ <b>그래도 공정성을 해결한 것은 아니다.</b> 한 계정이 자기 샤드를 비우면 같은 샤드에
 * 해싱된 회원(기본 64샤드에서 약 1.6%)은 같이 거부된다. 샤드를 <b>전부</b> 덮는 것도 가능하되
 * 비용이 선형이 아니다 — 쿠폰 수집 문제라 64샤드 전수에 평균 <b>301개</b> 계정이 들고 64개로는
 * 기대 커버리지가 63.5% 다(측정: 균일 분배 3,000회 시행). 호출자 단위 rps 공정성은
 * {@code modules/live/AGENTS.md} 가 형제 상한에 적는 그대로 상류(Ingress·WAF)의 몫이고
 * ("rps limiting belongs upstream"), 이 버킷들은 <b>CPU 천장</b>이다 — 샤딩이 바꾼 것은
 * <b>한 계정의 피해 반경</b>이지 그 결론이 아니다.
 *
 * <p>⚠️ <b>샤드 키에 프로세스 로컬 솔트를 섞는다 — 없으면 조준이 가능했다.</b> 샤드 키가
 * {@code sub}(=userId)이고 {@code String.hashCode} 는 JDK 규격이라 재현되는데, 미인증으로
 * 열린 목록 응답({@code GetLiveView.LiveRoomSummary.sellerId})이 방송 중 판매자의 userId 를
 * 준다. 솔트가 없으면 그 샤드를 오프라인에서 계산해 계정 하나(지속 8/s)로 특정 판매자를
 * 상시 끊을 수 있었다. 근거와 남는 위험은 {@code EnterRateLimiter.shardSalt} javadoc.
 *
 * <p>게스트는 샤딩하지 않는다. 신원이 없어 키가 없고, 요청마다 새로 만들 수 있는 값
 * (IP·헤더)을 키로 쓰면 형제가 반박한 그 모양이 된다.
 *
 * <p><b>끄는 스위치는 없다.</b> 빠져나갈 길은 한도를 올리는 것이고, 그러면 "제한 없음" 을
 * 표현하는 설정이 하나로 유지된다. ⚠️ 다만 버킷은 생성자에서 한 번 조립되고 이 레코드는
 * {@code @RefreshScope} 가 아니므로(이 저장소에 그 어노테이션은 0건) <b>한도 변경은 롤링
 * 재시작이다.</b> 입장이 전면 거부되는 사고에서 그것이 유일한 복구 수단이다.
 */
@ConfigurationProperties("live.enter.rate-limit")
public record EnterRateLimitProperties(
        Integer guestPermitsPerSecond,
        Integer guestBurst,
        Integer memberPermitsPerSecond,
        Integer memberBurst,
        Integer memberShards
) {
    /**
     * <b>CPU 상한에서 역산했고, 정상 트래픽은 측정되지 않았다.</b>
     *
     * <p>이 경로의 요청 하나는 {@code RoomTokenProvider} 의 <b>RS256 서명</b>을 한 번 치른다.
     * 실측(RSA-2048): 서명 685µs = <b>코어당 1,458건/s</b>. 형제 webhook 이 치르는 서명
     * <b>검증</b>은 31µs = 코어당 32,498건/s 로 22배 싸다 — 그래서 비용 등급이 다르다.
     *
     * <p>⚠️ <b>이 값이 "정상 트래픽보다 한참 위" 라는 근거는 없다.</b>
     * {@code modules/live/AGENTS.md} 가 형제 상한에 대해 요구하는 것이 정확히 그것인데
     * ("Keep it far above real traffic"), 여기서는 그 분모를 이 저장소에서 볼 수 없다
     * ({@code application*.yml} 미추적 · 배포 지표 부재). 구체적 수요 벡터 하나는 선다 —
     * <b>재접속 폭풍</b>: 룸 토큰 TTL 이 ~90s 이고 단일 사용이 아니므로 streaming-app 롤링
     * 재시작이면 전 게스트가 새 입장을 받아야 하고 그 전부가 이 버킷을 지난다.
     *
     * <p>⚠️ <b>분모가 CPU 하나만은 아니다.</b> 같은 요청이 {@code findById} 로 커넥션 풀을 쓰고,
     * Hikari 기본 최대 풀은 <b>10</b> 이며 이 저장소 어디에도 {@code maximum-pool-size} 가 없다 —
     * 한도를 올리면 풀 고갈 여유가 같이 줄어든다: 두 버킷 합 1,000/s 를 커넥션 10 개로 받으면
     * 커넥션당 100/s 이므로 {@code findById} 가 <b>10ms</b> 를 넘는 순간 풀이 고갈된다.
     * (서명이 트랜잭션 안에 있던 동안은 그 예산이 9.3ms 였다 — 0.7ms 를 같은 커넥션에서 썼다.)
     * 실제 지연은 이 저장소에서 측정할 수 없다.
     *
     * <p>다만 <b>서명은 그 분모에 들어가지 않는다</b>: {@code EnterLiveService.enter} 에
     * {@code @Transactional} 이 없어 단건 읽기가 자기 트랜잭션으로 끝나고 커넥션이 반납된 뒤에
     * 서명이 돈다. 그 부재 자체를 {@code EnterLiveServiceTest} 가 단정한다.
     *
     * <p>그래서 형제와 같은 높이(500/s)에서 시작한다. 두 버킷 합 1,000/s 는 서명 천장의 69%다 —
     * <b>그 위는 숫자로 못 올린다</b>: 한도를 더 올리면 RS256 서명이 이 엔드포인트의 용량 벽이
     * 되고, 그때 필요한 것은 큰 한도가 아니라 서명을 줄이는 설계다(캐시·HMAC 분리). 지금 한도는
     * 그 벽을 가리고 있을 뿐이다.
     *
     * <p>거부가 시작되면 그것이 첫 측정값이다. 낮춰서 셰이핑하려 들지 말 것 — 이 경로는 사람이
     * 쓰고, 게스트가 주 경로다({@code LiveSecurityConfig} 의 permitAll 목록).
     */
    private static final int DEFAULT_PERMITS_PER_SECOND = 500;
    /** 라이브 시작 직후·롤링 재시작 뒤 시청자가 한꺼번에 몰리는 것을 정상 트래픽으로 흡수한다. */
    private static final int DEFAULT_BURST = 1_000;
    /**
     * 회원 샤드 수.
     *
     * <p>64 를 고른 근거는 두 축의 교환이다. <b>크게</b> 할수록 한 계정의 피해 반경이 작아지고
     * (64 면 같이 거부되는 회원이 약 1.6%), <b>작게</b> 할수록 샤드당 몫이 커져 한 계정의
     * 일회분이 커진다. 기본값에서 샤드당 지속 <b>8/s</b>(= ⌈500/64⌉) · 버스트 <b>32</b>
     * (= ⌈1000/64⌉ × 헤드룸 2)다.
     *
     * <p>⚠️ <b>"한 샤드 수요는 전체의 1/64 이라 비율이 보존된다" 는 거짓이다</b> — 비율은
     * 기댓값에서만 보존되고 <b>버스트 보장은 보존되지 않는다.</b> 쪼개면 여유가 샤드 간에
     * 풀링되지 않아 이항분포의 꼬리가 그대로 거부가 된다. 그래서 샤드 버스트에 헤드룸을 둔다
     * ({@code EnterRateLimiter.BURST_HEADROOM} — 실측 12.0% → 0.0%).
     *
     * <p>메모리는 샤드 수에 선형이고 버킷 하나가 필드 몇 개다. 다만 그 "고정" 은
     * <b>상한이 있어야</b> 성립한다 — 아래 생성자가 샤드 수를 묶는다.
     */
    private static final int DEFAULT_MEMBER_SHARDS = 64;

    /**
     * 샤드 수 상한 — <b>배열이 "고정" 이라는 근거를 실제로 세우는 수</b>다.
     *
     * <p>하한만 닫아 두면 {@code member-permits-per-second: 2147483647} +
     * {@code member-shards: 2147483647} 같은 오타가 <b>검증을 통과</b>한다(그 조합은
     * {@code burst >= permits >= shards} 를 만족한다). 그러면 생성자가 그 길이의 배열을
     * 시도하고, 기동이 실패하는 자리가 설정 검증이 아니라 할당이 된다 — 그때 운영자가 받는 것은
     * 어느 키가 틀렸다는 메시지가 아니다.
     *
     * <p>4,096 은 쓸모의 끝에서 잡았다. 피해 반경이 이미 1/4,096 ≈ 0.024% 이고 그 위로는
     * 줄어드는 폭이 측정 불가에 가깝다. 샤드 전수를 덮는 비용은 반대로 계속 오르지만
     * (쿠폰 수집 n·H(n) ≈ 36,000 계정) 그 이득은 살포 공격에만 듣고, 같은 공격은 계정 수가
     * 아니라 집계 천장(전역 버킷)에서 먼저 막힌다.
     *
     * <p>메모리는 버킷당 {@code long} 3 + {@code AtomicLong} 2 + 참조 1 이라 4,096개도
     * 수백 KB 규모다(바이트 총량은 측정하지 않았다 — 상한의 근거는 쓸모이고 메모리가 아니다).
     */
    private static final int MAX_MEMBER_SHARDS = 4_096;

    public EnterRateLimitProperties {
        if (guestPermitsPerSecond == null) {
            guestPermitsPerSecond = DEFAULT_PERMITS_PER_SECOND;
        }
        if (guestBurst == null) {
            guestBurst = DEFAULT_BURST;
        }
        if (memberPermitsPerSecond == null) {
            memberPermitsPerSecond = DEFAULT_PERMITS_PER_SECOND;
        }
        if (memberBurst == null) {
            memberBurst = DEFAULT_BURST;
        }
        if (memberShards == null) {
            memberShards = DEFAULT_MEMBER_SHARDS;
        }
        validate("guest", guestPermitsPerSecond, guestBurst);
        validate("member", memberPermitsPerSecond, memberBurst);
        if (memberShards <= 0) {
            throw new IllegalArgumentException(
                    "live.enter.rate-limit.member-shards 는 양수여야 합니다: " + memberShards);
        }
        if (memberShards > MAX_MEMBER_SHARDS) {
            throw new IllegalArgumentException(
                    "live.enter.rate-limit.member-shards 는 " + MAX_MEMBER_SHARDS
                            + " 이하여야 합니다(그 위는 피해 반경이 더 줄지 않고 배열만 커진다): "
                            + memberShards);
        }
        if (memberPermitsPerSecond < memberShards) {
            // 샤드마다 ⌈permits/shards⌉ 를 주므로, 샤드가 더 많으면 샤드 리필의 합이 전역
            // permits 를 크게 넘어 샤드가 아무것도 묶지 못한다(전역만 남는다 = 샤딩 무효).
            throw new IllegalArgumentException(
                    "live.enter.rate-limit.member-shards 는 member-permits-per-second 이하여야 "
                            + "합니다(샤드당 허용량이 0 이 된다): "
                            + memberShards + " > " + memberPermitsPerSecond);
        }
    }

    private static void validate(String which, int permitsPerSecond, int burst) {
        if (permitsPerSecond <= 0) {
            throw new IllegalArgumentException("live.enter.rate-limit." + which
                    + "-permits-per-second 는 양수여야 합니다: " + permitsPerSecond);
        }
        if (burst < permitsPerSecond) {
            // 버킷이 초당 보충량보다 작으면 채워지는 즉시 넘쳐 지속 허용량이 설정값에 못 미친다.
            throw new IllegalArgumentException("live.enter.rate-limit." + which
                    + "-burst 는 " + which + "-permits-per-second 이상이어야 합니다: "
                    + burst + " < " + permitsPerSecond);
        }
    }

    /**
     * 429 응답의 {@code Retry-After} 에 실을 값.
     *
     * <p>토큰 하나가 차는 실제 시간(기본값에서 2ms)보다 훨씬 길게 잡는다 — 한도까지 갔다는 건
     * 이미 비정상 상황이라, 호출자를 곧바로 되돌려 보내는 것보다 한 박자 쉬게 하는 편이 낫다.
     * 초 단위 헤더라 1 미만은 표현할 수도 없다.
     */
    public Duration retryAfter() {
        return Duration.ofSeconds(1);
    }
}
