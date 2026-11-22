package com.sapari.liveapp.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 송출 키 재발급 상한. 형제 {@link EnterRateLimitProperties} 와 같은 규율이다 —
 * <b>미설정은 허용하고(기본값) 잘못된 값은 부팅에서 막는다.</b>
 *
 * <p>상한이 없으면 막히는 것은 이 엔드포인트가 아니라 <b>LiveKit</b> 이다. 요청 하나가 최대 5번을
 * 부르고({@code createIngress} 1 + {@code deleteIngress}·{@code listRoomIngress} 각 2), 방이
 * {@code Scheduled} 인 동안 횟수 제한이 없다 — 대기실에 둔 방 하나로 LiveKit 호출을 무한히 낼 수
 * 있었다. LiveKit 이 느려지면 같은 매니저를 쓰는 <b>방송 시작·종료까지</b> 함께 느려진다.
 *
 * <p>⚠️ 상한은 <b>파드 로컬</b>이라 실효치가 상한 × 레플리카다. 버킷은 생성자에서 조립되므로
 * <b>한도 변경은 롤링 재시작이다.</b>
 */
@ConfigurationProperties("live.ingress.reissue.rate-limit")
public record IngressReissueRateLimitProperties(
        Integer sellerPermitsPerSecond,
        Integer sellerBurst,
        Integer sellerShards
) {
    /**
     * 기본값 — <b>잰 값이 아니다.</b>
     *
     * <p>썸네일보다 낮게 둔다: 증폭 계수가 5배이고, 재발급은 키가 유출됐을 때 누르는 드문 수동
     * 동작이라 지속 요구가 거의 없다. 8/s · 버스트 16 · 샤드 8 이면 판매자 한 명의 지속 몫이
     * 1/s(= ⌈8/8⌉), 버스트가 4(= ⌈16/8⌉ × 헤드룸 2)다 — 연속으로 네 번까지는 즉시 받는다.
     * 전역 8/s 는 LiveKit 호출로 환산하면 최악 40/s 다. 운영에서 고치는 것이 복구 수단이다.
     *
     * <p>⚠️ 재발급은 <b>이전 키를 무효화</b>하므로, 상한에 걸려 429 를 받은 판매자는 이전 키를
     * 그대로 쓸 수 있다 — 거부가 방송을 막지 않는다. 입장 거부(방송 권한 상실)와 다른 점이고,
     * 그래서 여기는 낮게 잡아도 된다.
     */
    private static final CredentialIssueLimits DEFAULTS = new CredentialIssueLimits(8, 16, 8);

    /** 미설정을 기본값으로 채우고 잘못된 값은 던진다(부팅 차단). */
    public CredentialIssueLimits limits() {
        return CredentialIssueLimits.resolve("live.ingress.reissue.rate-limit", DEFAULTS,
                sellerPermitsPerSecond, sellerBurst, sellerShards);
    }
}
