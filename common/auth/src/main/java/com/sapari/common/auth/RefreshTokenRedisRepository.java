package com.sapari.common.auth;

import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import com.sapari.common.web.security.RefreshTokenStore;
import com.sapari.common.web.security.jwt.JwtProperties;

@Repository
@RequiredArgsConstructor
public class RefreshTokenRedisRepository implements RefreshTokenStore {

    private static final String KEY_PREFIX = "refresh-token:session:";

    private final StringRedisTemplate stringRedisTemplate;
    private final JwtProperties jwtProperties;

    @Override
    public void save(UUID sessionId, String refreshToken) {
        // 여러 기기 로그인을 허용하기 위해 사용자 ID가 아니라 로그인 세션 sid 기준으로 저장
        stringRedisTemplate.opsForValue()
                .set(createKey(sessionId), refreshToken, refreshTokenTtl());
    }

    @Override
    public Optional<String> findBySessionId(UUID sessionId) {
        // Refresh Token 재발급 시 token의 sid로 현재 세션의 저장된 Refresh Token을 찾음
        return Optional.ofNullable(stringRedisTemplate.opsForValue().get(createKey(sessionId)));
    }

    @Override
    public void deleteBySessionId(UUID sessionId) {
        // 로그아웃은 전체 사용자 세션이 아니라 현재 sid 세션만 종료
        stringRedisTemplate.delete(createKey(sessionId));
    }

    /**
     * Refresh Token 저장 TTL을 jwt 설정값과 동일하게 맞추기 위한 Duration 변환
     */
    private Duration refreshTokenTtl() {
        return Duration.ofSeconds(jwtProperties.refreshTokenExpirationSeconds());
    }

    private String createKey(UUID sessionId) {
        return KEY_PREFIX + sessionId;
    }
}
