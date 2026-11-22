package com.sapari.liveapp.security;

/**
 * 판매자 자격증명 발급 경로의 상한 값 — 판매자 전역 · 판매자 샤드 수.
 *
 * <p>미설정은 기본값, 잘못된 값은 던진다(부팅 차단). 가드는 {@link ChatReadLimits} 와 같다.
 *
 * <p><b>게스트 칸이 없는 것이 형제와 다른 점이다.</b> 이 경로들은 {@code /api/v1/lives/**} SELLER
 * 바닥에 걸려 미인증 요청이 401 로 끝나고, 비싼 일(POST 정책 서명 · LiveKit 호출)에는 닿지 않는다 —
 * 공개 경로인 채팅 조회({@link ChatReadLimits})와 달리 익명 폭주가 먹을 자원이 없으므로 버킷을 두면
 * 막는 비용이 없는 요청을 세게 된다.
 */
public record CredentialIssueLimits(
        int sellerPermitsPerSecond,
        int sellerBurst,
        int sellerShards
) {
    /** 샤드 수 상한 — 근거는 {@code EnterRateLimitProperties.MAX_MEMBER_SHARDS} 와 같다. */
    private static final int MAX_SELLER_SHARDS = 4_096;

    /**
     * 설정값에 기본값을 채우고 검증한다.
     *
     * @param prefix 오류 메시지에 실을 설정 접두사(예: {@code live.thumbnail.rate-limit})
     */
    static CredentialIssueLimits resolve(String prefix, CredentialIssueLimits defaults,
            Integer sellerPermitsPerSecond, Integer sellerBurst, Integer sellerShards) {
        CredentialIssueLimits limits = new CredentialIssueLimits(
                sellerPermitsPerSecond == null ? defaults.sellerPermitsPerSecond : sellerPermitsPerSecond,
                sellerBurst == null ? defaults.sellerBurst : sellerBurst,
                sellerShards == null ? defaults.sellerShards : sellerShards);
        if (limits.sellerPermitsPerSecond <= 0) {
            throw new IllegalArgumentException(prefix
                    + ".seller-permits-per-second 는 양수여야 합니다: " + limits.sellerPermitsPerSecond);
        }
        if (limits.sellerBurst < limits.sellerPermitsPerSecond) {
            // 버킷이 초당 보충량보다 작으면 채워지는 즉시 넘쳐 지속 허용량이 설정값에 못 미친다.
            throw new IllegalArgumentException(prefix + ".seller-burst 는 seller-permits-per-second "
                    + "이상이어야 합니다: " + limits.sellerBurst + " < " + limits.sellerPermitsPerSecond);
        }
        if (limits.sellerShards <= 0 || limits.sellerShards > MAX_SELLER_SHARDS) {
            throw new IllegalArgumentException(prefix + ".seller-shards 는 1~" + MAX_SELLER_SHARDS
                    + " 이어야 합니다: " + limits.sellerShards);
        }
        if (limits.sellerPermitsPerSecond < limits.sellerShards) {
            // 샤드가 더 많으면 샤드 리필 합이 전역을 크게 넘어 샤드가 아무것도 묶지 못한다.
            throw new IllegalArgumentException(prefix + ".seller-shards 는 seller-permits-per-second "
                    + "이하여야 합니다(샤드당 허용량이 0 이 된다): "
                    + limits.sellerShards + " > " + limits.sellerPermitsPerSecond);
        }
        return limits;
    }
}
