package com.sapari.user.infrastructure.security.redis;

import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import com.sapari.common.web.security.jwt.JwtProperties;

@Repository
@RequiredArgsConstructor
public class RefreshTokenRedisRepository {

    private static final String KEY_PREFIX = "refresh-token:user:";

    private final StringRedisTemplate stringRedisTemplate;
    private final JwtProperties jwtProperties;

    public void save(UUID userId, String refreshToken) {
        stringRedisTemplate.opsForValue()
                .set(createKey(userId), refreshToken, refreshTokenTtl());
    }

    public Optional<String> findByUserId(UUID userId) {
        return Optional.ofNullable(stringRedisTemplate.opsForValue().get(createKey(userId)));
    }

    public void delete(UUID userId) {
        stringRedisTemplate.delete(createKey(userId));
    }

    /**
     * Refresh Token 저장 TTL을 jwt 설정값과 동일하게 맞추기 위한 Duration 변환
     */
    private Duration refreshTokenTtl() {
        return Duration.ofSeconds(jwtProperties.refreshTokenExpirationSeconds());
    }

    private String createKey(UUID userId) {
        return KEY_PREFIX + userId;
    }
}
