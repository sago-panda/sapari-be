package com.sapari.common.auth;

import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import com.sapari.common.securityjwt.store.AccessTokenBlacklist;
import com.sapari.common.securityjwt.store.AccessTokenRevocationChecker;
import com.sapari.common.securityjwt.store.TokenStoreKeys;

@Repository
@RequiredArgsConstructor
public class AccessTokenBlacklistRedisRepository implements AccessTokenBlacklist, AccessTokenRevocationChecker {

    private static final String LOGOUT_VALUE = "logout";

    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public void save(UUID tokenId, Duration ttl) {
        stringRedisTemplate.opsForValue()
                .set(createKey(tokenId), LOGOUT_VALUE, ttl);
    }

    public boolean exists(UUID tokenId) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(createKey(tokenId)));
    }

    /**
     * 공통 JWT 필터가 Redis 구현을 직접 알지 않도록 blacklist 조회 결과를 공통 인터페이스로 노출
     */
    @Override
    public boolean isRevoked(UUID tokenId) {
        return exists(tokenId);
    }

    private String createKey(UUID tokenId) {
        return TokenStoreKeys.ACCESS_TOKEN_BLACKLIST_PREFIX + tokenId;
    }
}
