package com.sapari.common.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Repository;

import com.sapari.common.securityjwt.store.RefreshTokenRotationResult;
import com.sapari.common.securityjwt.store.RefreshTokenStore;
import com.sapari.common.securityjwt.store.TokenStoreKeys;
import com.sapari.global.time.TimeProvider;

/**
 * 세션 키 값은 언제나 현재 refresh jti 하나다. 회전으로 밀려난 jti 는 별도 유예 키(ZSet, member=jti,
 * score=유예만료epochMillis)에 둔다 — 세션 키 형식을 바꾸지 않아야 이 스크립트를 모르는 Pod 와 섞여 돌거나
 * 롤백해도 정상 jti 가 재사용으로 오판되지 않는다.
 * 유예 키는 {@code {세션키}:grace} 로, 해시태그가 세션 키 이름 전체라 Redis Cluster 에서도 같은 슬롯에 놓인다.
 */
@Repository
public class RefreshTokenRedisRepository implements RefreshTokenStore {

    static final Duration MAX_REUSE_GRACE = Duration.ofSeconds(30);

    // 반환: 1 = 회전, 2 = 유예 안의 밀려난 jti 재제시, 0 = 거부(세션 없음·유예 밖·모르는 jti)
    // 유예 창 안에 밀려난 jti 를 모두 기억해, 동시 요청 사이에 회전이 한 번 더 끼어도 늦게 온 요청을 지킨다.
    private static final DefaultRedisScript<Long> ROTATE_SCRIPT = new DefaultRedisScript<>(
            """
            local current = redis.call('GET', KEYS[1])
            if current == false then
                return 0
            end
            local now = tonumber(ARGV[4])
            if current == ARGV[1] then
                redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
                local graceMillis = tonumber(ARGV[5])
                if graceMillis > 0 then
                    redis.call('ZADD', KEYS[2], now + graceMillis, ARGV[1])
                    redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', now)
                    redis.call('PEXPIRE', KEYS[2], graceMillis)
                end
                return 1
            end
            local graceUntil = redis.call('ZSCORE', KEYS[2], ARGV[1])
            if graceUntil and now < tonumber(graceUntil) then
                return 2
            end
            return 0
            """,
            Long.class
    );

    private final StringRedisTemplate stringRedisTemplate;
    private final TimeProvider timeProvider;
    private final Duration reuseGrace;

    public RefreshTokenRedisRepository(
            StringRedisTemplate stringRedisTemplate,
            TimeProvider timeProvider,
            @Value("${jwt.refresh-token-reuse-grace:5s}") Duration reuseGrace
    ) {
        // 유예가 길수록 탈취된 직전 refresh 로 access token 을 받을 수 있는 창이 넓어진다. 0 이면 유예를 끈다.
        if (reuseGrace == null || reuseGrace.isNegative() || reuseGrace.compareTo(MAX_REUSE_GRACE) > 0) {
            throw new IllegalArgumentException("jwt.refresh-token-reuse-grace 는 0초 이상 30초 이하여야 합니다.");
        }
        this.stringRedisTemplate = stringRedisTemplate;
        this.timeProvider = timeProvider;
        this.reuseGrace = reuseGrace;
    }

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
     * 현재 저장된 Refresh Token ID가 기대값과 같을 때 새 ID로 원자적으로 교체하고, 교체된 ID를 유예 시간 동안 기억한다.
     */
    @Override
    public RefreshTokenRotationResult rotate(
            UUID sessionId,
            UUID expectedRefreshTokenId,
            UUID newRefreshTokenId,
            Duration ttl
    ) {
        Long result = stringRedisTemplate.execute(
                ROTATE_SCRIPT,
                List.of(createKey(sessionId), createGraceKey(sessionId)),
                expectedRefreshTokenId.toString(),
                newRefreshTokenId.toString(),
                String.valueOf(ttl.toMillis()),
                String.valueOf(timeProvider.now().toEpochMilli()),
                String.valueOf(reuseGrace.toMillis())
        );

        if (Long.valueOf(1L).equals(result)) {
            return RefreshTokenRotationResult.ROTATED;
        }
        if (Long.valueOf(2L).equals(result)) {
            return RefreshTokenRotationResult.REUSED_WITHIN_GRACE;
        }
        return RefreshTokenRotationResult.REJECTED;
    }

    /**
     * 로그인 세션의 Refresh Token 정보와 사용자별 세션 ZSet의 sid를 삭제한다.
     */
    @Override
    public void deleteBySessionId(UUID userId, UUID sessionId) {
        stringRedisTemplate.delete(List.of(createKey(sessionId), createGraceKey(sessionId)));
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
            String sessionKey = TokenStoreKeys.REFRESH_TOKEN_SESSION_PREFIX + sessionId;
            keys.add(sessionKey);
            keys.add(createGraceKey(sessionKey));
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

    private String createGraceKey(UUID sessionId) {
        return createGraceKey(createKey(sessionId));
    }

    private String createGraceKey(String sessionKey) {
        return "{" + sessionKey + "}:grace";
    }

    private String createUserSessionsKey(UUID userId) {
        return TokenStoreKeys.REFRESH_TOKEN_USER_SESSIONS_PREFIX + userId;
    }
}
