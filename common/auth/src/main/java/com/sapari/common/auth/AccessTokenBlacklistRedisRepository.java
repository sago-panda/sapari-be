package com.sapari.common.auth;

import lombok.RequiredArgsConstructor;

import java.time.Duration;

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
    public void save(String accessToken, Duration ttl) {
        stringRedisTemplate.opsForValue()
                .set(createKey(accessToken), LOGOUT_VALUE, ttl);
    }

    public boolean exists(String accessToken) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(createKey(accessToken)));
    }

    /**
     * 공통 JWT 필터가 Redis 구현을 직접 알지 않도록 blacklist 조회 결과를 공통 인터페이스로 노출
     */
    @Override
    public boolean isRevoked(String accessToken) {
        return exists(accessToken);
    }

    private String createKey(String accessToken) {
        return KEY_PREFIX + accessToken;
    }
}
