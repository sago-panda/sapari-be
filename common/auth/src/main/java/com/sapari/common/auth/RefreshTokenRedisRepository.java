package com.sapari.common.auth;

import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import com.sapari.common.securityjwt.store.RefreshTokenStore;
import com.sapari.common.securityjwt.store.TokenStoreKeys;

@Repository
@RequiredArgsConstructor
public class RefreshTokenRedisRepository implements RefreshTokenStore {

    private static final DefaultRedisScript<Long> ROTATE_SCRIPT = new DefaultRedisScript<>(
            """
            local current = redis.call('GET', KEYS[1])
            if current == false then
                return 0
            end
            if current ~= ARGV[1] then
                return 0
            end
            redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
            return 1
            """,
            Long.class
    );

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 로그인 세션의 현재 Refresh Token ID를 저장한다.
     */
    @Override
    public void save(UUID sessionId, UUID refreshTokenId, Duration ttl) {
        stringRedisTemplate.opsForValue()
                .set(createKey(sessionId), refreshTokenId.toString(), ttl);
    }

    /**
     * 현재 저장된 Refresh Token ID가 기대값과 같을 때 새 ID로 원자적으로 교체한다.
     */
    @Override
    public boolean rotate(UUID sessionId, UUID expectedRefreshTokenId, UUID newRefreshTokenId, Duration ttl) {
        Long result = stringRedisTemplate.execute(
                ROTATE_SCRIPT,
                List.of(createKey(sessionId)),
                expectedRefreshTokenId.toString(),
                newRefreshTokenId.toString(),
                String.valueOf(ttl.toMillis())
        );

        return Long.valueOf(1L).equals(result);
    }

    /**
     * 로그인 세션의 Refresh Token 정보를 삭제한다.
     */
    @Override
    public void deleteBySessionId(UUID sessionId) {
        stringRedisTemplate.delete(createKey(sessionId));
    }

    private String createKey(UUID sessionId) {
        return TokenStoreKeys.REFRESH_TOKEN_SESSION_PREFIX + sessionId;
    }
}
