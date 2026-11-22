package com.sapari.customer.infrastructure.redis;

import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import com.sapari.customer.domain.repository.SocialLoginCodeRepository;

@Repository
@RequiredArgsConstructor
public class SocialLoginCodeRedisRepository implements SocialLoginCodeRepository {

    private static final String KEY_PREFIX = "login:social:code:";
    private static final Duration TTL = Duration.ofMinutes(3);

    private final StringRedisTemplate stringRedisTemplate;

    public void save(String code, String value) {
        stringRedisTemplate.opsForValue().set(createKey(code), value, TTL);
    }

    /**
     * temporary_login_code는 토큰 교환용 one-time code이므로 Redis GETDEL로 조회와 삭제를 원자적으로 처리한다.
     */
    @Override
    public Optional<String> consumeByCode(String code) {
        return Optional.ofNullable(stringRedisTemplate.opsForValue().getAndDelete(createKey(code)));
    }

    private String createKey(String code) {
        return KEY_PREFIX + code;
    }
}
