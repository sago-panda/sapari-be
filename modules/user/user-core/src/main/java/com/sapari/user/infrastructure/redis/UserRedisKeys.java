package com.sapari.user.infrastructure.redis;

/**
 * user 도메인이 <b>소유</b>하는 Redis 채널 이름.
 *
 * <p>구독자(chat 등)는 이 상수를 참조할 수 없다 — {@code -core} 는 남이 의존하지 않으므로 각자
 * 문자열을 들고 있다. 그래서 이 이름을 바꾸면 <b>구독이 조용히 끊긴다</b>(발행은 성공하고 아무도
 * 받지 않는다). 형제 {@code LiveRedisKeys.roomEndedChannel()} 이 같은 처지이고, 그쪽 구독 어댑터가
 * "live 소유 채널 계약" 이라고 적어 둔 이유가 이것이다.
 */
public final class UserRedisKeys {

    private UserRedisKeys() {
    }

    /**
     * 탈퇴 신청 채널. 이름은 <b>들어간 상태</b>를 따른다({@code UserStatus.WITHDRAWING}) —
     * 형제가 {@code live:room:ended}(상태 {@code Ended})로 같은 규칙을 쓴다.
     */
    public static String accountWithdrawingChannel() {
        return "user:account:withdrawing";
    }
}
