package com.sapari.common.auth;

import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import com.sapari.common.securityjwt.jwt.JwtProperties;
import com.sapari.common.securityjwt.store.SessionRevocationChecker;
import com.sapari.common.securityjwt.store.SessionRevocationStore;
import com.sapari.common.securityjwt.store.TokenStoreKeys;

@Repository
@RequiredArgsConstructor
public class SessionRevocationRedisRepository implements SessionRevocationStore, SessionRevocationChecker {

    private static final String REVOKED_VALUE = "revoked";

    private final StringRedisTemplate stringRedisTemplate;
    private final JwtProperties jwtProperties;

    /**
     * 로그인 세션을 Access Token 최대 수명 동안 폐기 상태로 저장한다.
     */
    @Override
    public void revoke(UUID sessionId) {
        stringRedisTemplate.opsForValue()
                .set(
                        createKey(sessionId),
                        REVOKED_VALUE,
                        Duration.ofSeconds(jwtProperties.accessTokenExpirationSeconds())
                );
    }

    @Override
    public void revokeAll(UUID userId) {
        Set<String> sessionIds = stringRedisTemplate.opsForSet()
                .members(TokenStoreKeys.REFRESH_TOKEN_USER_SESSIONS_PREFIX + userId);
        if (sessionIds == null || sessionIds.isEmpty()) {
            return;
        }

        for (String sessionId : sessionIds) {
            revoke(UUID.fromString(sessionId));
        }
    }

    /**
     * 로그인 세션 폐기 마커가 있으면 폐기된 세션으로 판단한다.
     */
    @Override
    public boolean isRevoked(UUID sessionId) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(createKey(sessionId)));
    }

    private String createKey(UUID sessionId) {
        return TokenStoreKeys.REVOKED_SESSION_PREFIX + sessionId;
    }
}
