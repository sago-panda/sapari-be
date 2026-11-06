package com.sapari.live.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 룸 토큰 서명 캐시 설정 — <b>같은 claim 으로 또 서명하지 않기</b> 위한 것.
 *
 * <p><b>왜 있나.</b> 입장마다 RS256 개인키 서명 1회고 실측 <b>688µs</b>(RSA-2048, aarch64,
 * n=20,000, p50 682.8µs, p99 735.8µs) = <b>1코어 1,453건/s</b> 다. 처리량은 코어에 거의 선형이다
 * (같은 실행 실측: 1스레드 1,436 · 2 → 2,777 · 4 → 5,450건/s).
 *
 * <p>문제는 그 수가 아니라 <b>분모</b>다. 입장 상한 합이 1,000/s 인데 그것이 천장의 몇 %인지는
 * 코어 수가 정하고 — 1코어면 69.6%, 2코어면 36.0%, 4코어면 18.3% — <b>이 저장소에는 배포 코어 수가
 * 적힌 곳이 없다</b>(실측: {@code infra} 에 태스크·파드 정의 0건, {@code vcpu} 유일 히트는 LiveKit
 * 포트 설명). 게다가 688µs 자체도 aarch64 값이라 x86 버스터블에서는 또 다르다. 그래서 분모를
 * 확정하는 대신 <b>비용을 지우는</b> 쪽을 골랐다. {@code EnterRateLimitProperties} 가 그 처방을
 * 이미 적어 뒀다 — "필요한 것은 큰 한도가 아니라 서명을 줄이는 설계다(캐시·HMAC 분리)".
 *
 * <p><b>HMAC 쪽은 기각했다.</b> 실측 HS256 6.4µs = 1코어 156,680건/s 로 <b>108배</b> 싸다. 그래도
 * 안 쓰는 이유는 RS256 을 고른 근거가 비용이 아니라 <b>비대칭</b>이기 때문이다 — chat 은 공개키로
 * 검증만 하고 <b>발급은 못 해야</b> 한다. 대칭키로 바꾸면 chat 이 룸 토큰을 위조할 수 있고,
 * 그러면 {@code owner} claim 이 chat 의 강퇴·원문 노출 게이트의 근거라는 구조가 무너진다.
 * 108배는 그 경계를 파는 값이다.
 *
 * <p><b>걷히는 몫은 절반이다.</b> {@code EnterLiveService.issueGuestToken} 이 호출마다 새 임의
 * UUID 를 {@code sub} 로 박아(게스트 세션을 구분해야 하므로 그래야 한다) 게스트 키는 절대 반복되지
 * 않는다. 게스트 버킷이 1,000/s 중 500/s 라 상한이 절반이다. 덮는 절반이 {@code modules/live/AGENTS.md}
 * 가 이 경로의 최악 수요로 직접 이름 붙인 <b>"1,000-member reconnect storm"</b> 과 겹친다 —
 * 그게 회원이다.
 *
 * @param enabled    기본 켜짐. 끄면 데코레이터를 <b>아예 끼우지 않는다</b>(호출 경로에 0줄 남는다)
 * @param slots      슬롯 수. <b>2의 거듭제곱이어야 한다</b>({@link #SLOTS_MUST_BE_POWER_OF_TWO})
 * @param ttlSeconds 한 번 서명한 토큰을 재사용하는 창(초)
 */
@ConfigurationProperties("live.room-token.cache")
public record RoomTokenCacheProperties(
        Boolean enabled,
        Integer slots,
        Long ttlSeconds
) {

    /**
     * 기본 슬롯 수. 회원 지속 허용량 500/s × 기본 TTL 30s = <b>동시 작업집합 15,000</b> 이라
     * 그 위의 2의 거듭제곱으로 잡는다. 이보다 작게 두면 서로 밀어내 적중률이 떨어지는데, 그 상태는
     * {@code live.token.signature{source}} 에서 {@code signed} 비율로 보인다.
     */
    private static final int DEFAULT_SLOTS = 16_384;

    /**
     * 기본 TTL. {@link #TTL_MUST_LEAVE_HALF_THE_LIFE} 때문에 발급 TTL(기본 90s)의 절반 이하여야
     * 하고, 그 안에서 재접속 폭풍을 덮을 만큼 길게 잡는다.
     */
    private static final long DEFAULT_TTL_SECONDS = 30L;

    /**
     * 슬롯 수 상한 — <b>메모리에서 역산했다.</b>
     *
     * <p>슬롯 하나의 배열 칸은 참조 하나지만 <b>무게는 붙어 있는 토큰 문자열</b>이다. 실측:
     * 회원 claim 전량(닉네임 + 이메일)의 RS256 토큰이 <b>730자</b>
     * ({@code RoomTokenCachePropertiesTest.theCachedTokenLengthIsMeasuredNotGuessed}). base64url 이라
     * compact string 으로 1바이트/자 + {@code Slot}·{@code RoomTokenClaims} 까지 보면 점유 슬롯당
     * 대략 1KB 다.
     *
     * <p>⚠️ 처음에 {@code 1 << 20} 으로 뒀는데 그건 <b>문자열만 약 730MB</b> 를 허용하는 값이었다 —
     * 설정 한 줄로 그만큼 먹일 수 있으면 상한이 아니다. 실측이 그걸 반증해서 내렸다.
     *
     * <p>{@code 1 << 16} = 65,536 ≈ <b>토큰 문자열 45MB</b>(슬롯·claim 까지 보면 64MB) 로 둔다. 근거는 <b>상한이 만들 수 있는 작업집합</b>이다:
     * 회원 지속 허용량 기본 500/s × 캐시 TTL 상한(발급 TTL 의 절반 = 기본 45s) = 서로 다른 키
     * <b>22,500</b> 이고, 65,536 은 그 2.9배다. 그보다 키우면 적중률은 더 오르지 않고
     * (담을 키가 없다) 토큰 문자열만 쌓인다 — 형제 {@code EnterRateLimitProperties.MAX_MEMBER_SHARDS}
     * 가 "그 위는 피해 반경이 더 줄지 않고 배열만 커진다" 로 같은 모양의 상한을 둔 것과 같다.
     */
    private static final int MAX_SLOTS = 1 << 16;

    static final String SLOTS_MUST_BE_POWER_OF_TWO =
            "live.room-token.cache.slots 는 2의 거듭제곱이어야 합니다";

    static final String TTL_MUST_LEAVE_HALF_THE_LIFE =
            "live.room-token.cache.ttl-seconds 는 live.room-token.expiration-seconds 의 절반 이하여야 합니다";

    /**
     * <b>2의 거듭제곱을 올려 맞추지 않고 거부한다.</b>
     *
     * <p>거듭제곱이면 인덱스가 {@code & (slots-1)} 로 떨어져 나눗셈이 없다. 그런데 조용히 올려
     * 맞추지 않는 이유는 성능이 아니다 — 형제 {@code EnterRateLimitProperties} 가 바로 그 실수를
     * 실측으로 적어 뒀다: 샤드 버킷을 내림 나눗셈으로 잡아 <b>설정이 500/s 라고 적힌 동안 실제로는
     * 448/s 가 걸렸다</b>. 설정에 적힌 수가 실제 수여야 한다.
     */
    public RoomTokenCacheProperties {
        if (enabled == null) {
            enabled = Boolean.TRUE;
        }
        if (slots == null) {
            slots = DEFAULT_SLOTS;
        }
        if (ttlSeconds == null) {
            ttlSeconds = DEFAULT_TTL_SECONDS;
        }
        if (slots <= 0) {
            throw new IllegalArgumentException(
                    "live.room-token.cache.slots 는 양수여야 합니다: " + slots);
        }
        if (slots > MAX_SLOTS) {
            throw new IllegalArgumentException("live.room-token.cache.slots 는 " + MAX_SLOTS
                    + " 이하여야 합니다(그 위는 적중률이 더 오르지 않고 토큰 문자열만 쌓인다): " + slots);
        }
        if (Integer.bitCount(slots) != 1) {
            throw new IllegalArgumentException(SLOTS_MUST_BE_POWER_OF_TWO
                    + "(올려 맞추지 않는 이유는 설정에 적힌 수가 실제 수여야 하기 때문이다): " + slots);
        }
        if (ttlSeconds <= 0) {
            throw new IllegalArgumentException(
                    "live.room-token.cache.ttl-seconds 는 양수여야 합니다: " + ttlSeconds);
        }
    }

    /**
     * 발급 TTL 과의 관계를 검사한다 — <b>캐시 TTL 은 발급 TTL 의 절반 이하</b>.
     *
     * <p>캐시된 토큰은 {@code exp} 가 <b>처음 서명한 시각</b>에 고정돼 있다. 그래서 캐시 나이가
     * 늘수록 받는 쪽의 잔여 수명이 줄고, 하한이 {@code expirationSeconds - ttlSeconds} 다. 절반을
     * 넘기지 않으면 어떤 적중에서도 잔여 수명이 발급 TTL 의 절반 이상임이 보장된다(기본값에서
     * 90s 발급 · 30s 캐시 → 최악 60s).
     *
     * <p>이 검사가 <b>다른 레코드의 값을 봐야 해서</b> 컴팩트 생성자에 들어가지 못한다. 그래서
     * 빈 조립 시점에 {@code RoomTokenConfig} 가 부른다 — 정리 잡들의 네 개 기동 가드와 같은
     * fail-closed 모양이고, 어긴 상태는 운영에서 "토큰이 벌써 만료됐다" 로 처음 드러난다.
     */
    void validateAgainst(RoomTokenProperties issuance) {
        long issuanceSeconds = issuance.expirationSeconds();
        // 곱이 아니라 나눗셈으로 비교한다 — 도달 가능한 모든 값에서 동일하고(90/45 통과 ·
        // 90/46 거부 · 59/30 거부 · 61/30 통과) ttl 이 2^62 를 넘을 때 곱이 음수가 되어
        // 가드가 통과하는 면이 사라진다. 그 값은 1.5e11 년이라 운영 결함은 아니지만, 비교
        // 모양을 바꾸는 값이 한 토큰이라 면을 남길 이유가 없다.
        if (ttlSeconds > issuanceSeconds / 2) {
            throw new IllegalArgumentException(TTL_MUST_LEAVE_HALF_THE_LIFE
                    + " — 캐시 적중의 잔여 수명 하한이 (발급 - 캐시) 이기 때문이다: "
                    + ttlSeconds + "s > " + issuanceSeconds + "s / 2");
        }
    }
}
