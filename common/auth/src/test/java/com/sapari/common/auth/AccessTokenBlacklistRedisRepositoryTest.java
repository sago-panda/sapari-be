package com.sapari.common.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@DisplayName("Access Token blacklist Redis 저장소 테스트")
class AccessTokenBlacklistRedisRepositoryTest {

    private static final UUID TOKEN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String KEY = "access-token:blacklist:" + TOKEN_ID;

    private final StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOperations = valueOperations();
    private final AccessTokenBlacklistRedisRepository repository =
            new AccessTokenBlacklistRedisRepository(stringRedisTemplate);

    @Test
    @DisplayName("Access Token jti를 blacklist에 TTL과 함께 저장한다")
    void saveStoresAccessTokenWithTtl() {
        // given
        Duration ttl = Duration.ofMinutes(10);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        // when
        repository.save(TOKEN_ID, ttl);

        // then
        verify(valueOperations).set(KEY, "logout", ttl);
    }

    @Test
    @DisplayName("blacklist key가 있으면 폐기된 jti로 판단한다")
    void isRevokedReturnsTrueWhenAccessTokenExists() {
        // given
        when(stringRedisTemplate.hasKey(KEY)).thenReturn(true);

        // when
        boolean revoked = repository.isRevoked(TOKEN_ID);

        // then
        assertThat(revoked).isTrue();
    }

    @Test
    @DisplayName("blacklist key가 없으면 폐기되지 않은 jti로 판단한다")
    void isRevokedReturnsFalseWhenAccessTokenDoesNotExist() {
        // given
        when(stringRedisTemplate.hasKey(KEY)).thenReturn(false);

        // when
        boolean revoked = repository.isRevoked(TOKEN_ID);

        // then
        assertThat(revoked).isFalse();
    }

    @SuppressWarnings("unchecked")
    private ValueOperations<String, String> valueOperations() {
        return mock(ValueOperations.class);
    }
}
