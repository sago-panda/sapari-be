package com.sapari.common.securityjwt.store;

/**
 * 토큰 스토어 Redis 키 컨벤션의 단일 소스.
 *
 * <p>키를 만드는 곳은 {@code common/auth} 의 세 저장소뿐이고, 그것을 읽는 소비자가 여럿이다 —
 * api-app 의 공통 {@code JwtAuthenticationFilter} 와 live-app 의 {@code TokenRevocationFilter}.
 * 소비자가 키 문자열을 자기 쪽에 복제하면 접두사 한 글자가 "폐기된 적 없음" 으로 조용히 읽힌다.
 *
 * <p>⚠️ 한때 여기에 "reactive read 측(streaming-app)이 동일한 키를 쓰도록" 이라 적혀 있었다.
 * <b>streaming-app 은 이 클래스를 참조하지 않는다</b>(실측: 저장소 전체에서 {@code common} 밖
 * 참조 0건). 그 앱은 룸 토큰(RS256)만 검증하고 이 키를 읽지 않는다 — 설계가 그쪽에서 blacklist
 * 조회를 걷어내기로 했기 때문이다({@code RoomTokenVerifier} javadoc).
 */
public final class TokenStoreKeys {

    /** Access Token 폐기 목록 키 prefix. 뒤에 jti(tokenId, UUID)를 붙인다. */
    public static final String ACCESS_TOKEN_BLACKLIST_PREFIX = "access-token:blacklist:";

    /** Refresh Token 저장 키 prefix. 뒤에 sid(sessionId, UUID)를 붙인다. */
    public static final String REFRESH_TOKEN_SESSION_PREFIX = "refresh-token:session:";

    /** 폐기된 로그인 세션 키 prefix. 뒤에 sid(sessionId, UUID)를 붙인다. */
    public static final String REVOKED_SESSION_PREFIX = "revoked-session:";

    /** 사용자별 로그인 세션 Set 키 prefix. 뒤에 userId(UUID)를 붙인다. */
    public static final String REFRESH_TOKEN_USER_SESSIONS_PREFIX = "refresh-token:user-sessions:";

    private TokenStoreKeys() {
    }
}
