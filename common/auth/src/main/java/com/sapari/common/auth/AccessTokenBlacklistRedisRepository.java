package com.sapari.common.auth;

import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import com.sapari.common.web.security.AccessTokenBlacklist;
import com.sapari.common.web.security.AccessTokenRevocationChecker;

@Repository
@RequiredArgsConstructor
public class AccessTokenBlacklistRedisRepository implements AccessTokenBlacklist, AccessTokenRevocationChecker {

    private static final String KEY_PREFIX = "access-token:blacklist:";
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
        return KEY_PREFIX + tokenId;
    }
}
