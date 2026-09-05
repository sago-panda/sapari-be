package com.sapari.chat.infrastructure.redis;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;

import com.sapari.chat.domain.repository.ChatBanWriteRepository;

import lombok.RequiredArgsConstructor;

/**
 * {@code chat:banned:{userId}} 쓰기 어댑터 — 만료는 값이 아니라 TTL이 표현한다.
 *
 * <p>읽는 쪽({@code ChatBanRedisRepository})이 키의 존재만 보므로 값은 자리를 채우는 문자 하나면 된다.
 * 값에 만료 시각을 담으면 그걸 해석하는 코드가 읽기·쓰기 양쪽에 생기고, 두 해석이 갈리는 순간 밴이
 * 조용히 풀리거나 풀리지 않는다.
 *
 * <p><b>이 키에는 타입 충돌 위험이 없다.</b> 쓰기가 {@code SET}이라 남이 먼저 쓴 값을 덮고, 읽기가
 * {@code EXISTS}라 타입을 보지 않는다. 강퇴 명단({@code SISMEMBER})이 WRONGTYPE에 노출돼 별도 예외로
 * 갈라야 했던 것과 다르므로 그 분류를 여기까지 넓히지 않는다.
 */
@RequiredArgsConstructor
public class ChatBanWriteRedisRepository implements ChatBanWriteRepository {

    /** 값은 읽히지 않는다. 존재만이 의미다. */
    private static final String PRESENT = "1";

    private final StringRedisTemplate redisTemplate;

    @Override
    public void ban(UUID userId, Instant expiresAt, Instant now) {
        String key = ChatRedisKeys.banned(userId);
        if (expiresAt == null) {
            // 영구 밴 — TTL을 붙이지 않는다. 기존 키에 TTL이 남아 있었다면 SET이 그것까지 지운다.
            redisTemplate.opsForValue().set(key, PRESENT);
            return;
        }
        Duration ttl = Duration.between(now, expiresAt);
        if (ttl.isZero() || ttl.isNegative()) {
            // 이미 지난 만료다. 여기서 TTL 0으로 쓰면 Redis가 거부하거나 즉시 사라져, 밴이 아닌 것을
            // 밴처럼 기록하는 흔적만 남는다. 정본에 없는 상태이므로 아무것도 쓰지 않는다.
            return;
        }
        redisTemplate.opsForValue().set(key, PRESENT, ttl);
    }
}
