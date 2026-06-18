package com.sapari.common.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import com.sapari.global.time.TimeProvider;

@DisplayName("Refresh Token Redis 저장소 테스트")
class RefreshTokenRedisRepositoryTest {

    private static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(14);
    private static final Instant NOW = Instant.parse("2026-06-16T00:00:00Z");

    private final StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOperations = valueOperations();
    private final ZSetOperations<String, String> zSetOperations = zSetOperations();
    private final TimeProvider timeProvider = mock(TimeProvider.class);
    private final RefreshTokenRedisRepository repository =
            new RefreshTokenRedisRepository(stringRedisTemplate, timeProvider);

    @Test
    @DisplayName("세션 ID 기준으로 현재 Refresh Token ID를 TTL과 함께 저장한다")
    void saveStoresRefreshTokenWithTtl() {
        // given
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID refreshTokenId = UUID.randomUUID();
        String key = "refresh-token:session:" + sessionId;
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        givenUserSessionsZSetMaxScore(userId, NOW.plus(REFRESH_TOKEN_TTL).toEpochMilli());

        // when
        repository.save(userId, sessionId, refreshTokenId, REFRESH_TOKEN_TTL);

        // then
        verify(valueOperations).set(
                key,
                refreshTokenId.toString(),
                REFRESH_TOKEN_TTL
        );
    }

    @Test
    @DisplayName("사용자 ID 기준 세션 ZSet에 sid 만료시각을 저장하고 만료 sid를 정리한다")
    void saveStoresRefreshTokenAndUserSessionZSetWithExpirationScore() {
        // given
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID refreshTokenId = UUID.randomUUID();
        String refreshKey = "refresh-token:session:" + sessionId;
        String userSessionKey = "refresh-token:user-sessions:" + userId;
        long nowMillis = NOW.toEpochMilli();
        long expiresAtMillis = NOW.plus(REFRESH_TOKEN_TTL).toEpochMilli();
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        givenUserSessionsZSetMaxScore(userId, expiresAtMillis);

        // when
        repository.save(userId, sessionId, refreshTokenId, REFRESH_TOKEN_TTL);

        // then
        verify(valueOperations).set(refreshKey, refreshTokenId.toString(), REFRESH_TOKEN_TTL);
        verify(zSetOperations).add(userSessionKey, sessionId.toString(), expiresAtMillis);
        verify(zSetOperations).removeRangeByScore(userSessionKey, Double.NEGATIVE_INFINITY, nowMillis);
        verify(stringRedisTemplate).expire(userSessionKey, REFRESH_TOKEN_TTL);
    }

    @Test
    @DisplayName("사용자 세션 ZSet TTL은 남은 sid 중 가장 늦은 만료시각 기준으로 설정한다")
    void saveExpiresUserSessionZSetByMaxScore() {
        // given
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID refreshTokenId = UUID.randomUUID();
        long maxExpiresAtMillis = NOW.plus(REFRESH_TOKEN_TTL).plus(Duration.ofHours(1)).toEpochMilli();
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        givenUserSessionsZSetMaxScore(userId, maxExpiresAtMillis);

        // when
        repository.save(userId, sessionId, refreshTokenId, REFRESH_TOKEN_TTL);

        // then
        verify(stringRedisTemplate).expire(
                "refresh-token:user-sessions:" + userId,
                Duration.ofMillis(maxExpiresAtMillis - NOW.toEpochMilli())
        );
    }

    @Test
    @DisplayName("저장된 Refresh Token ID가 기대값과 같으면 새 ID로 교체한다")
    void rotateReturnsTrueWhenRefreshTokenIdMatches() {
        // given
        UUID sessionId = UUID.randomUUID();
        UUID expectedRefreshTokenId = UUID.randomUUID();
        UUID newRefreshTokenId = UUID.randomUUID();
        String key = "refresh-token:session:" + sessionId;
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(List.of(key)),
                eq(expectedRefreshTokenId.toString()),
                eq(newRefreshTokenId.toString()),
                eq(String.valueOf(REFRESH_TOKEN_TTL.toMillis()))
        )).thenReturn(1L);

        // when
        boolean rotated = repository.rotate(sessionId, expectedRefreshTokenId, newRefreshTokenId, REFRESH_TOKEN_TTL);

        // then
        assertThat(rotated).isTrue();
    }

    @Test
    @DisplayName("저장된 Refresh Token ID가 기대값과 다르면 교체하지 않는다")
    void rotateReturnsFalseWhenRefreshTokenIdDoesNotMatch() {
        // given
        UUID sessionId = UUID.randomUUID();
        UUID expectedRefreshTokenId = UUID.randomUUID();
        UUID newRefreshTokenId = UUID.randomUUID();
        String key = "refresh-token:session:" + sessionId;
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(List.of(key)),
                eq(expectedRefreshTokenId.toString()),
                eq(newRefreshTokenId.toString()),
                eq(String.valueOf(REFRESH_TOKEN_TTL.toMillis()))
        )).thenReturn(0L);

        // when
        boolean rotated = repository.rotate(sessionId, expectedRefreshTokenId, newRefreshTokenId, REFRESH_TOKEN_TTL);

        // then
        assertThat(rotated).isFalse();
    }

    @Test
    @DisplayName("사용자 ID와 세션 ID 기준으로 Refresh Token과 사용자 세션 ZSet의 sid를 삭제한다")
    void deleteRemovesRefreshTokenAndUserSessionZSetMember() {
        // given
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        String key = "refresh-token:session:" + sessionId;
        String userSessionKey = "refresh-token:user-sessions:" + userId;
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);

        // when
        repository.deleteBySessionId(userId, sessionId);

        // then
        verify(stringRedisTemplate).delete(key);
        verify(zSetOperations).remove(userSessionKey, sessionId.toString());
    }

    @Test
    @DisplayName("사용자 전체 세션 삭제 시 만료 sid를 정리한 뒤 남은 sid의 Refresh Token만 삭제한다")
    void deleteAllByUserIdDeletesActiveSessionKeysAfterRemovingExpiredMembers() {
        // given
        UUID userId = UUID.randomUUID();
        UUID activeSessionId = UUID.randomUUID();
        String userSessionKey = "refresh-token:user-sessions:" + userId;
        when(timeProvider.now()).thenReturn(NOW);
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.range(userSessionKey, 0, -1))
                .thenReturn(Set.of(activeSessionId.toString()));

        // when
        repository.deleteAllByUserId(userId);

        // then
        verify(zSetOperations).removeRangeByScore(
                userSessionKey,
                Double.NEGATIVE_INFINITY,
                NOW.toEpochMilli()
        );
        verify(stringRedisTemplate).delete(List.of(
                "refresh-token:session:" + activeSessionId,
                userSessionKey
        ));
    }

    @SuppressWarnings("unchecked")
    private ValueOperations<String, String> valueOperations() {
        return mock(ValueOperations.class);
    }

    @SuppressWarnings("unchecked")
    private ZSetOperations<String, String> zSetOperations() {
        return mock(ZSetOperations.class);
    }

    private void givenUserSessionsZSetMaxScore(UUID userId, long maxScore) {
        String userSessionKey = "refresh-token:user-sessions:" + userId;
        ZSetOperations.TypedTuple<String> tuple = typedTuple(maxScore);
        when(timeProvider.now()).thenReturn(NOW);
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.reverseRangeWithScores(userSessionKey, 0, 0))
                .thenReturn(Set.of(tuple));
    }

    @SuppressWarnings("unchecked")
    private ZSetOperations.TypedTuple<String> typedTuple(double score) {
        ZSetOperations.TypedTuple<String> tuple = mock(ZSetOperations.TypedTuple.class);
        when(tuple.getScore()).thenReturn(score);
        return tuple;
    }
}
