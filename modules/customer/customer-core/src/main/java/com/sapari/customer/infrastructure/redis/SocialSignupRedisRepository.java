package com.sapari.customer.infrastructure.redis;

import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import com.sapari.customer.domain.repository.SocialSignupRepository;

@Repository
@RequiredArgsConstructor
public class SocialSignupRedisRepository implements SocialSignupRepository {

    private static final String KEY_PREFIX = "signup:social:info:";
    private static final Duration TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate stringRedisTemplate;

    public void save(String sid, String value) {
        stringRedisTemplate.opsForValue().set(createKey(sid), value, TTL);
    }

    public Optional<String> findBySid(String sid) {
        return Optional.ofNullable(stringRedisTemplate.opsForValue().get(createKey(sid)));
    }

    public void delete(String sid) {
        stringRedisTemplate.delete(createKey(sid));
    }

    public boolean exists(String sid) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(createKey(sid)));
    }

    private String createKey(String sid) {
        return KEY_PREFIX + sid;
    }
}
