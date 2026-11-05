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
 * <p>⚠️ <b>가른 것은 "미인증 → 회원" 한 방향뿐이다.</b> 회원 버킷은 집단 전역 하나라
 * <b>회원 한 명이 회원 전체를 굶힐 수 있다</b> — 가입에 캡차·이메일 인증이 없으므로 계정
 * 하나로 그 버킷을 상시 비울 수 있고, 같은 호스트가 {@code Authorization} 을 빼면 게스트
 * 버킷도 같이 비운다. 이것은 <b>닫지 않았다.</b> 호출자 단위 rps 공정성은
 * {@code modules/live/AGENTS.md} 가 형제 상한에 적는 그대로 상류(Ingress·WAF)의 몫이고
 * ("rps limiting belongs upstream"), 이 버킷들은 <b>CPU 천장</b>이다. 그 구분을 지우고
 * "가용성을 해결했다" 로 읽지 말 것.
 *
 * <p><b>principal 단위 버킷이 아닌 이유.</b> {@code sub} 는 검증된 JWT 에서 오니 조작은
 * 불가하지만, 가입이 셀프서비스라 <b>무한 맵</b>은 형제가 반박한 IP 축과 같은 메모리 증식
 * 경로가 된다. ⚠️ 그 반박이 덮지 <b>않는</b> 축이 있다 — 고정 크기 샤드 배열은 메모리가
 * 구조적으로 묶이고 {@code roomId} 는 DB 행 수로 유한하다. 둘 다 검토만 하고 받지 않았다:
 * 이 티켓의 축은 CPU 천장이고 공정성은 위에 적은 대로 상류 몫이다.
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
        Integer memberBurst
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
 * 그 서명이 {@code EnterLiveService} 의 {@code @Transactional(readOnly = true)} <b>안</b>에
 * 있어 커넥션을 점유한 채 0.7ms 를 더 쓴다(선재 구조이고 이 티켓이 안 건드렸다). Hikari 기본
 * 최대 풀은 <b>10</b> 이고 이 저장소 어디에도 {@code maximum-pool-size} 가 없다 — 한도를
 * 올리면 풀 고갈 여유가 같이 줄어든다(100/s 에서 {@code findById} ≥ 100ms 였던 것이 1,000/s
 * 에서 ≥ 9.3ms 다). 그 지연은 여기서 측정할 수 없다. 서명을 트랜잭션 밖으로 빼는 것이 그
 * 결합을 끊는 수정이고 별 티켓이다.
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
        validate("guest", guestPermitsPerSecond, guestBurst);
        validate("member", memberPermitsPerSecond, memberBurst);
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
