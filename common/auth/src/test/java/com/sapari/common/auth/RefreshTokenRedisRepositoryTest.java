package com.sapari.common.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.sapari.common.securityjwt.jwt.JwtProperties;

@DisplayName("Refresh Token Redis 저장소 테스트")
class RefreshTokenRedisRepositoryTest {

    private static final String SECRET = "test-secret-key-for-jwt-provider-32bytes";
    private static final String REFRESH_TOKEN = "refresh-token";
    private static final long REFRESH_TOKEN_EXPIRATION_SECONDS = 1209600L;

    private final StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOperations = valueOperations();
    private final RefreshTokenRedisRepository repository = new RefreshTokenRedisRepository(
            stringRedisTemplate,
            new JwtProperties("test-issuer", SECRET, 3600L, REFRESH_TOKEN_EXPIRATION_SECONDS)
    );

    @Test
    @DisplayName("세션 ID 기준으로 Refresh Token을 TTL과 함께 저장한다")
    void saveStoresRefreshTokenWithTtl() {
        // given
        UUID sessionId = UUID.randomUUID();
        String key = "refresh-token:session:" + sessionId;
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        // when
        repository.save(sessionId, REFRESH_TOKEN);

        // then
        verify(valueOperations).set(
                key,
                REFRESH_TOKEN,
                Duration.ofSeconds(REFRESH_TOKEN_EXPIRATION_SECONDS)
        );
    }

    @Test
    @DisplayName("세션 ID로 Refresh Token을 조회한다")
    void findBySessionIdReturnsRefreshToken() {
        // given
        UUID sessionId = UUID.randomUUID();
        String key = "refresh-token:session:" + sessionId;
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(key)).thenReturn(REFRESH_TOKEN);

        // when
        Optional<String> refreshToken = repository.findBySessionId(sessionId);

        // then
        assertThat(refreshToken).contains(REFRESH_TOKEN);
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
