package com.sapari.common.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.ValueOperations;

@DisplayName("Refresh Token Redis 저장소 테스트")
class RefreshTokenRedisRepositoryTest {

    private static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(14);

    private final StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOperations = valueOperations();
    private final RefreshTokenRedisRepository repository = new RefreshTokenRedisRepository(stringRedisTemplate);

    @Test
    @DisplayName("세션 ID 기준으로 현재 Refresh Token ID를 TTL과 함께 저장한다")
    void saveStoresRefreshTokenWithTtl() {
        // given
        UUID sessionId = UUID.randomUUID();
        UUID refreshTokenId = UUID.randomUUID();
        String key = "refresh-token:session:" + sessionId;
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        // when
        repository.save(sessionId, refreshTokenId, REFRESH_TOKEN_TTL);

        // then
        verify(valueOperations).set(
                key,
                refreshTokenId.toString(),
                REFRESH_TOKEN_TTL
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
    @DisplayName("세션 ID 기준으로 Refresh Token을 삭제한다")
    void deleteRemovesRefreshToken() {
        // given
        UUID sessionId = UUID.randomUUID();
        String key = "refresh-token:session:" + sessionId;

        // when
        repository.deleteBySessionId(sessionId);

        // then
        verify(stringRedisTemplate).delete(key);
    }

    @SuppressWarnings("unchecked")
    private ValueOperations<String, String> valueOperations() {
        return mock(ValueOperations.class);
    }
}
