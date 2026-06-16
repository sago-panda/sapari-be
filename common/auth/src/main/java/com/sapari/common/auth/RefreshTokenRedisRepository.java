package com.sapari.common.auth;

import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Repository;

import com.sapari.common.securityjwt.store.RefreshTokenStore;
import com.sapari.common.securityjwt.store.TokenStoreKeys;
import com.sapari.global.time.TimeProvider;

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
    private final TimeProvider timeProvider;

    /**
     * 로그인 세션의 현재 Refresh Token ID를 저장하고, 사용자별 세션 ZSet에 sid 만료시각을 score로 기록한다.
     */
    @Override
    public void save(UUID userId, UUID sessionId, UUID refreshTokenId, Duration ttl) {
        Instant now = timeProvider.now();
        long nowMillis = now.toEpochMilli();
        long expiresAtMillis = now.plus(ttl).toEpochMilli();
        String userSessionsKey = createUserSessionsKey(userId);
        ZSetOperations<String, String> zSetOperations = stringRedisTemplate.opsForZSet();

        stringRedisTemplate.opsForValue()
                .set(createKey(sessionId), refreshTokenId.toString(), ttl);
        zSetOperations.add(userSessionsKey, sessionId.toString(), expiresAtMillis);

        // ZSet은 멤버별 TTL이 없으므로 score(만료시각) 기준으로 stale sid를 명시적으로 정리한다.
        removeExpiredUserSessions(zSetOperations, userSessionsKey, nowMillis);
        expireUserSessionsKeyAtMaxScore(zSetOperations, userSessionsKey, nowMillis);
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
     * 로그인 세션의 Refresh Token 정보와 사용자별 세션 ZSet의 sid를 삭제한다.
     */
    @Override
    public void deleteBySessionId(UUID userId, UUID sessionId) {
        stringRedisTemplate.delete(createKey(sessionId));
        stringRedisTemplate.opsForZSet().remove(createUserSessionsKey(userId), sessionId.toString());
    }

    @Override
    public void deleteAllByUserId(UUID userId) {
        String userSessionsKey = createUserSessionsKey(userId);
        ZSetOperations<String, String> zSetOperations = stringRedisTemplate.opsForZSet();

        // 전체 로그아웃/탈퇴는 아직 살아 있는 sid만 대상으로 삼아 불필요한 session key 삭제를 줄인다.
        removeExpiredUserSessions(zSetOperations, userSessionsKey, timeProvider.now().toEpochMilli());

        Set<String> sessionIds = zSetOperations.range(userSessionsKey, 0, -1);
        if (sessionIds == null || sessionIds.isEmpty()) {
            stringRedisTemplate.delete(userSessionsKey);
            return;
        }

        List<String> keys = new ArrayList<>();
        for (String sessionId : sessionIds) {
            keys.add(TokenStoreKeys.REFRESH_TOKEN_SESSION_PREFIX + sessionId);
        }
        keys.add(userSessionsKey);
        stringRedisTemplate.delete(keys);
    }

    private void removeExpiredUserSessions(
            ZSetOperations<String, String> zSetOperations,
            String userSessionsKey,
            long nowMillis
    ) {
        zSetOperations.removeRangeByScore(userSessionsKey, Double.NEGATIVE_INFINITY, nowMillis);
    }

    private void expireUserSessionsKeyAtMaxScore(
            ZSetOperations<String, String> zSetOperations,
            String userSessionsKey,
            long nowMillis
    ) {
        // 사용자별 세션 목록 키는 남은 sid 중 가장 늦게 만료되는 세션까지만 유지한다.
        Set<ZSetOperations.TypedTuple<String>> maxScoreTuples =
                zSetOperations.reverseRangeWithScores(userSessionsKey, 0, 0);
        if (maxScoreTuples == null || maxScoreTuples.isEmpty()) {
            stringRedisTemplate.delete(userSessionsKey);
            return;
        }

        Double maxScore = maxScoreTuples.iterator().next().getScore();
        if (maxScore == null || maxScore <= nowMillis) {
            stringRedisTemplate.delete(userSessionsKey);
            return;
        }

        stringRedisTemplate.expire(userSessionsKey, Duration.ofMillis(maxScore.longValue() - nowMillis));
    }

    private String createKey(UUID sessionId) {
        return TokenStoreKeys.REFRESH_TOKEN_SESSION_PREFIX + sessionId;
    }

    private String createUserSessionsKey(UUID userId) {
        return TokenStoreKeys.REFRESH_TOKEN_USER_SESSIONS_PREFIX + userId;
    }
}
