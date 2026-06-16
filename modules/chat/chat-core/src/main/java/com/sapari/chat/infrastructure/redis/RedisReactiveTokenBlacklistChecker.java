package com.sapari.chat.infrastructure.redis;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;

import com.sapari.chat.application.port.ReactiveTokenBlacklistChecker;
import com.sapari.common.securityjwt.store.TokenStoreKeys;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * 로그아웃·강제만료 Access Token 차단 — 키 존재 여부만 읽는다.
 * 등재는 common/auth(블로킹) 책임이고, 키는 {@link TokenStoreKeys}를 공유해 drift를 막는다.
 */
@Component
@RequiredArgsConstructor
public class RedisReactiveTokenBlacklistChecker implements ReactiveTokenBlacklistChecker {

    private final ReactiveStringRedisTemplate redisTemplate;

    @Override
    public Mono<Boolean> isBlacklisted(String jti) {
        return redisTemplate.hasKey(TokenStoreKeys.ACCESS_TOKEN_BLACKLIST_PREFIX + jti);
    }
}
