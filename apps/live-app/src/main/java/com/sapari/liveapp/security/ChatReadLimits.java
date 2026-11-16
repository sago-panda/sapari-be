package com.sapari.liveapp.security;

/**
 * 공개 채팅 조회(이력·공지) 상한의 값 — 게스트 전역 · 회원 전역 · 회원 샤드 수.
 *
 * <p>미설정은 기본값, 잘못된 값은 던진다(부팅 차단). 가드는 {@link EnterRateLimitProperties} 와 같다.
 */
public record ChatReadLimits(
        int guestPermitsPerSecond,
        int guestBurst,
        int memberPermitsPerSecond,
        int memberBurst,
        int memberShards
) {
    /** 샤드 수 상한 — 근거는 {@code EnterRateLimitProperties.MAX_MEMBER_SHARDS} 와 같다. */
    private static final int MAX_MEMBER_SHARDS = 4_096;

    /**
     * 설정값에 기본값을 채우고 검증한다.
     *
     * @param prefix 오류 메시지에 실을 설정 접두사(예: {@code live.chat.history.rate-limit})
     */
    static ChatReadLimits resolve(String prefix, ChatReadLimits defaults,
            Integer guestPermitsPerSecond, Integer guestBurst,
            Integer memberPermitsPerSecond, Integer memberBurst, Integer memberShards) {
        ChatReadLimits limits = new ChatReadLimits(
                guestPermitsPerSecond == null ? defaults.guestPermitsPerSecond : guestPermitsPerSecond,
                guestBurst == null ? defaults.guestBurst : guestBurst,
                memberPermitsPerSecond == null ? defaults.memberPermitsPerSecond : memberPermitsPerSecond,
                memberBurst == null ? defaults.memberBurst : memberBurst,
                memberShards == null ? defaults.memberShards : memberShards);
        validate(prefix, "guest", limits.guestPermitsPerSecond, limits.guestBurst);
        validate(prefix, "member", limits.memberPermitsPerSecond, limits.memberBurst);
        if (limits.memberShards <= 0 || limits.memberShards > MAX_MEMBER_SHARDS) {
            throw new IllegalArgumentException(prefix + ".member-shards 는 1~" + MAX_MEMBER_SHARDS
                    + " 이어야 합니다: " + limits.memberShards);
        }
        if (limits.memberPermitsPerSecond < limits.memberShards) {
            // 샤드가 더 많으면 샤드 리필 합이 전역을 크게 넘어 샤드가 아무것도 묶지 못한다.
            throw new IllegalArgumentException(prefix + ".member-shards 는 member-permits-per-second "
                    + "이하여야 합니다(샤드당 허용량이 0 이 된다): "
                    + limits.memberShards + " > " + limits.memberPermitsPerSecond);
        }
        return limits;
    }

    private static void validate(String prefix, String which, int permitsPerSecond, int burst) {
        if (permitsPerSecond <= 0) {
            throw new IllegalArgumentException(prefix + "." + which
                    + "-permits-per-second 는 양수여야 합니다: " + permitsPerSecond);
        }
        if (burst < permitsPerSecond) {
            // 버킷이 초당 보충량보다 작으면 채워지는 즉시 넘쳐 지속 허용량이 설정값에 못 미친다.
            throw new IllegalArgumentException(prefix + "." + which + "-burst 는 " + which
                    + "-permits-per-second 이상이어야 합니다: " + burst + " < " + permitsPerSecond);
        }
    }
}
