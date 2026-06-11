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

    public Optional<String> findByCode(String code) {
        return Optional.ofNullable(stringRedisTemplate.opsForValue().get(createKey(code)));
    }

    public void delete(String code) {
        stringRedisTemplate.delete(createKey(code));
    }

    private String createKey(String code) {
        return KEY_PREFIX + code;
    }
}
