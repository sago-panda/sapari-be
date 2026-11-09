package com.sapari.live.infrastructure.media;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.LongSupplier;

import com.sapari.live.application.port.LiveMetrics;
import com.sapari.live.application.port.RoomTokenClaims;
import com.sapari.live.application.port.RoomTokenIssuer;
import com.sapari.live.application.port.RoomTokenSignatureSource;
import com.sapari.live.infrastructure.config.RoomTokenCacheProperties;

/**
 * 같은 claim 으로 또 서명하지 않는 {@link RoomTokenIssuer} 데코레이터.
 *
 * <p>걷어내는 비용은 RS256 개인키 서명 <b>688µs</b>(실측, RSA-2048 · aarch64 · n=20,000 ·
 * p99 735.8µs)다. 왜 이것이 설계 변경의 대상인지와 HMAC 을 기각한 근거는
 * {@link RoomTokenCacheProperties} javadoc 에 있다.
 *
 * <p><b>스테레오타입을 붙이지 않는다.</b> 형제 {@code MeteredLiveMediaManager} 와 같다 —
 * {@code RoomTokenConfig} 가 {@code @Bean @Primary} 로 등록하고, 꺼져 있으면 원본을 그대로
 * 돌려줘 호출 경로에 아무것도 끼지 않는다. 여기에 {@code @Component @Primary} 를 달면 자기
 * 타입을 자기에게 주입하려는 모양이 된다.
 *
 * <h2>이 경로가 왜 안전한가 — 캐시 밖에 남는 것들</h2>
 *
 * <p>서명만 캐시한다. {@code EnterLiveService.enter} 가 <b>서명보다 앞에서</b> 정본을 읽고
 * ({@code findById}) 진행 중인지 판정하며({@code canEnterLive}), 폐기 검사는 그보다 더 앞인
 * 필터({@code TokenRevocationFilter})다. 그래서 적중해도 <b>방 상태 판정과 폐기 판정은 매 호출
 * 그대로 돈다</b> — 캐시가 돌려주는 것은 "이 claim 에 대한 서명" 뿐이고, 그 claim 을 발급할
 * 자격이 있는지는 캐시 밖에서 매번 다시 묻는다.
 *
 * <h2>충돌이 여기선 PII 유출이다 — 그래서 키를 비교한다</h2>
 *
 * <p>형제 {@code EnterRateLimiter} 는 같은 모양의 고정크기 배열을 쓰는데, 거기서 해시 충돌의
 * 결과는 <b>두 계정이 버킷을 공유</b>하는 것(공정성 저하)이다. 여기서는 다르다 — 충돌한 슬롯을
 * 그냥 믿으면 <b>A 에게 B 용으로 발급된 토큰</b>을 준다. 그 토큰에는 B 의 {@code nickname} 과
 * {@code email} 이 실려 있고, {@code sub}·{@code owner} 가 B 의 것이라 chat 에서 <b>B 로
 * 행동</b>하게 된다. 신원 혼동과 PII 유출이 한 번에 난다.
 *
 * <p>그래서 슬롯에 <b>전체 키를 같이 저장하고 읽을 때 {@code equals} 로 비교</b>한다. 불일치는
 * 적중이 아니라 미스이고, 그 자리를 덮어쓴다. {@link RoomTokenClaims} 가 record 라 {@code equals}
 * 가 여섯 필드 전수에 대해 자동으로 옳다 — 그 전수성이 이 보호의 근거이므로, 그 레코드에 필드를
 * 더하면 비교도 자동으로 따라온다(손으로 센 목록이 아니다).
 *
 * <h2>게스트는 읽지도 쓰지도 않는다</h2>
 *
 * <p>{@code issueGuestToken} 이 호출마다 새 임의 UUID 를 {@code sub} 로 박으므로 게스트 키는
 * 절대 반복되지 않는다 — 적중률이 <b>구조적으로 0</b> 이다. 그냥 미스로 흘려보내면 되는 게
 * 아니라 <b>쓰기까지 막아야</b> 한다: 안 막으면 게스트 500/s 가 매번 새 키로 슬롯을 덮어
 * <b>회원 항목을 밀어내고</b>, 결과는 캐시를 넣고도 적중률이 바닥인 상태다. 캐시를 키워도 안
 * 낫는다(쓰는 쪽이 무한하다).
 *
 * <h2>시계</h2>
 *
 * <p>{@code TimeProvider} 가 아니라 {@code nanoTime} 을 쓴다. 재는 것이 <b>경과 시간</b>이고
 * 벽시계 시각이 아니다 — 이 저장소의 {@code TimeProvider} 규칙은 도메인 시각을 위한 것이고,
 * 여기서 벽시계를 쓰면 NTP 보정이 뒤로 가는 순간 나이가 음수가 되어 만료가 멈춘다. 형제
 * {@code EnterRateLimiter} 도 같은 이유로 {@code nanoClock} 을 받는다.
 */
public class CachingRoomTokenIssuer implements RoomTokenIssuer {

    /** 캐시를 타지 않는 역할. {@code EnterLiveService.issueGuestToken} 이 싣는 값이다. */
    private static final String GUEST_ROLE = "GUEST";

    private final RoomTokenIssuer delegate;
    private final LiveMetrics metrics;
    private final LongSupplier nanoClock;

    private final AtomicReferenceArray<Slot> slots;
    private final int mask;
    private final long ttlNanos;

    /**
     * 조준을 막는 프로세스 로컬 salt — 형제 {@code EnterRateLimiter.shardKey} 와 <b>목적</b>이 같다.
     *
     * <p>⚠️ <b>방식은 다르고, 보장도 같지 않다.</b> 형제는 키 혼합(HmacSHA256)이라 키 없이는 두 주체가
     * 같은 샤드인지 계산할 수 없다. 이쪽은 해시에 salt 를 <b>더하고</b> xor-fold 할 뿐이라, 전체 32비트
     * 해시가 같은 두 키는 salt 와 무관하게 같은 슬롯이다 — 그 관계는 salt 로 지워지지 않는다. 미스는
     * 서명으로 끝나 비용 문제에 그치므로 보강은 따로 미뤘다(SPR-185 범위 밖).
     *
     * <p>키의 {@code userId} 는 공개 목록 응답({@code GetLiveView.LiveRoomSummary.sellerId})으로
     * 밖에 나가고 {@code UUID.hashCode} 는 JDK 가 명세한 함수다. salt 가 없으면 공격자가 특정
     * 판매자의 슬롯을 오프라인으로 계산해 그 자리만 계속 덮어 <b>그 사람의 적중만</b> 없앨 수
     * 있다. 그것만으로 기능이 깨지지는 않지만(미스는 그냥 서명한다) 비용 공격의 표적이 된다.
     * salt 는 슬롯 번호를 오프라인으로 계산하는 것을 막는다 — 위 단락의 전체 해시 충돌은 예외다.
     */
    private final int salt = UUID.randomUUID().hashCode();

    public CachingRoomTokenIssuer(RoomTokenIssuer delegate, LiveMetrics metrics,
                                  RoomTokenCacheProperties properties, LongSupplier nanoClock) {
        this.delegate = delegate;
        this.metrics = metrics;
        this.nanoClock = nanoClock;
        this.slots = new AtomicReferenceArray<>(properties.slots());
        this.mask = properties.slots() - 1;
        this.ttlNanos = properties.ttlSeconds() * 1_000_000_000L;
    }

    @Override
    public String issue(RoomTokenClaims claims) {
        if (GUEST_ROLE.equals(claims.role())) {
            // 세지도 않는다 — 분모에 넣으면 적중률이 구조적으로 눌려 "슬롯이 작다" 와 구분이 안 된다.
            return delegate.issue(claims);
        }

        int index = indexOf(claims);
        long now = nanoClock.getAsLong();

        Slot cached = slots.get(index);
        if (cached != null && cached.key().equals(claims) && now - cached.mintedAt() < ttlNanos) {
            metrics.roomTokenIssued(RoomTokenSignatureSource.CACHED);
            return cached.token();
        }

        String token = delegate.issue(claims);
        // 덮어쓴다. 경쟁하는 두 호출이 같은 슬롯을 쓰면 뒤에 쓴 쪽이 남는데, 둘 다 자기 claim 에
        // 대해 유효한 토큰이므로 어느 쪽이 남아도 옳다(읽는 쪽이 키를 비교한다).
        slots.set(index, new Slot(claims, token, now));
        metrics.roomTokenIssued(RoomTokenSignatureSource.SIGNED);
        return token;
    }

    /**
     * 슬롯 번호. {@code slots} 가 2의 거듭제곱이라({@link RoomTokenCacheProperties} 가 기동에서
     * 강제) 마스크 한 번으로 떨어진다.
     *
     * <p><b>부호를 따로 지우지 않는다.</b> {@code hashCode} 는 음수일 수 있지만 {@code mask} 가
     * {@code slots - 1} 이고 {@code slots} 가 양수이므로 <b>{@code mask} 는 음이 아니다</b> — 즉
     * 31번째 비트가 0 이고, {@code h & mask} 는 <b>임의의 {@code int h}</b> 에 대해 {@code [0, mask]}
     * 다. 여기에 {@code & Integer.MAX_VALUE} 를 더 쓰면 효과가 0 인 노이즈다.
     *
     * <p>⚠️ 이 근거에 <b>슬롯 상한값을 쓰지 않는다.</b> 한때 "상한이 {@code 1 << 20} 이라 상위
     * 12비트가 0" 으로 적었는데, 같은 커밋에서 상한을 {@code 1 << 16} 으로 내리면서 그 문장과
     * 파생 비트수가 함께 거짓이 됐다. 상한에 기대면 상한을 건드릴 때마다 이 자리가 다시 거짓이
     * 된다 — 양수성만으로 참이므로 그렇게 적는다.
     *
     * <p><b>상위 비트를 아래로 섞는다</b>({@code h ^ (h >>> 16)}) — 다만 <b>이 키에서는 효과가
     * 측정되지 않는다.</b> 한때 이 자리에 "record {@code hashCode} 는 필드 해시를 31 배수로 누적한
     * 값이라 하위 비트에 편향이 남을 수 있다" 고 적었는데 <b>재 보니 거짓이었다</b>(실측: 실제
     * {@link RoomTokenClaims} 로 slots=16,384 · 키 16,384 · salt 5종 —
     * 섞음 점유 10,330~10,402 / chi² 16,258~16,504, 안 섞음 점유 10,380~10,409 / chi² 16,066~16,348.
     * 균등분포 기대가 점유 10,357 · chi² ≈ df 16,383 이라 양쪽 다 거기 있다. slots=4,096 도 같다).
     *
     * <p>이유는 키 구성이다 — 지배 필드가 {@code UUID} 둘이고 {@code UUID.hashCode} 가 이미 상·하위
     * 32비트를 xor-fold 하므로 31 누적이 하위 비트에 실어 줄 편향이 애초에 없다. {@code HashMap.spread}
     * 가 상정하는 입력(작은 정수·공통 접두 문자열)과 이 키는 다르다.
     *
     * <p><b>그래도 남겨 둔다.</b> 비용이 한 줄이고, 키 구성이 바뀌면(문자열 필드가 지배하게 되거나
     * {@code UUID} 가 빠지면) 전제가 바뀌는데 그때 이 자리를 다시 보게 만드는 장치가 없다. 보수적
     * 처방이라고 적는 쪽이 "편향이 있다" 고 단정하는 것보다 정확하다.
     */
    private int indexOf(RoomTokenClaims claims) {
        int h = claims.hashCode() * 31 + salt;
        return (h ^ (h >>> 16)) & mask;
    }

    /**
     * 슬롯 하나. <b>키를 토큰과 함께</b> 들고 있는 것이 이 레코드의 존재 이유다(위 PII 단락).
     *
     * @param mintedAt {@code nanoTime} 기준 발급 시각 — 벽시계가 아니다(위 시계 단락)
     */
    private record Slot(RoomTokenClaims key, String token, long mintedAt) { }
}
