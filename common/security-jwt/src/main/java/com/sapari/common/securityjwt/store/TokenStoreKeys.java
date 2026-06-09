package com.sapari.common.securityjwt.store;

/**
 * 토큰 스토어 Redis 키 컨벤션의 단일 소스.
 * blocking write 측(common/auth)과 reactive read 측(streaming-app)이 동일한 키를 쓰도록 상수를 공유한다.
 */
public final class TokenStoreKeys {

    /** Access Token 폐기 목록 키 prefix. 뒤에 jti(tokenId, UUID)를 붙인다. */
    public static final String ACCESS_TOKEN_BLACKLIST_PREFIX = "access-token:blacklist:";

    /** Refresh Token 저장 키 prefix. 뒤에 sid(sessionId, UUID)를 붙인다. */
    public static final String REFRESH_TOKEN_SESSION_PREFIX = "refresh-token:session:";

    /** 폐기된 로그인 세션 키 prefix. 뒤에 sid(sessionId, UUID)를 붙인다. */
    public static final String REVOKED_SESSION_PREFIX = "revoked-session:";

    private TokenStoreKeys() {
    }
}
