package com.sapari.chat.infrastructure.redis;

import java.util.List;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import com.sapari.chat.domain.exception.KickStoreCorruptedException;
import com.sapari.chat.domain.repository.ChatKickWriteRepository;

import lombok.RequiredArgsConstructor;

/**
 * {@code chat:kicked:{roomId}} SET에 강퇴자를 올리는 블로킹 어댑터.
 *
 * <p>키 문자열은 {@link ChatRedisKeys}에서 온다 — 이 패키지에 두는 이유가 그것이다. 읽는 쪽
 * ({@code ChatKickRedisRepository})과 같은 소스를 보므로 두 스택이 서로 다른 키를 가리킬 수 없다.
 *
 * <p>스테레오타입을 붙이지 않는다. chat-core를 함께 의존하는 streaming-app에는 블로킹 Redis 빈이 없어,
 * 스캔되면 그 앱의 부팅이 깨진다. 이 어댑터는 블로킹 스택을 가진 앱이 명시로 등록한다.
 */
@RequiredArgsConstructor
public class ChatKickWriteRedisRepository implements ChatKickWriteRepository {

    /**
     * 추가와 만료 해제를 한 번에 — 둘로 나누면 그 사이에 만료가 실제로 도래하거나 {@code PERSIST}만
     * 실패했을 때 <b>만료가 붙은 채로 사람만 늘어난 명단</b>이 남는다. 그 키는 만료 시각에 통째로
     * 사라지고, 그때 강퇴자 전원이 조용히 돌아온다.
     *
     * <p>{@code SADD}가 만료를 건드리지 않는다는 것이 이 스크립트의 존재 이유다. 방 종료 신호가 잘못
     * 왔던 방에는 회수용 만료만 남아 있는데, 그 위에 그냥 추가하면 새 강퇴까지 그 만료를 물려받는다.
     *
     * <p>반환값은 쓰지 않는다. 이미 명단에 있었는지는 여기서 가릴 일이 아니다 — 중복 강퇴를 가르는 것은
     * 강퇴 로그의 유니크 제약이고, 그 판정은 이 호출보다 앞에서 이미 끝나 있다.
     */
    private static final RedisScript<Long> REGISTER_KICKED = RedisScript.of("""
            redis.call('SADD', KEYS[1], ARGV[1])
            redis.call('PERSIST', KEYS[1])
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    @Override
    public void register(UUID roomId, UUID userId) {
        String key = ChatRedisKeys.kicked(roomId);
        try {
            // 실패는 그대로 던진다 — 명단에 못 올렸다는 건 그 사람이 곧 돌아온다는 뜻이라,
            // 호출자가 강퇴를 실패로 처리하고 재시도할 수 있어야 한다.
            redisTemplate.execute(REGISTER_KICKED, List.of(key), userId.toString());
        } catch (RuntimeException e) {
            // 낫지 않는 실패만 갈라낸다. 키가 우리 타입이 아니면 이 쓰기는 Redis가 멀쩡해도 계속 실패한다 —
            // 재시도는 멱등이면서 영원히 실패한다. 일시 장애와 같은 예외로 두면 운영자에게는 "곧 나을 오류"로
            // 보이고, 정작 사람이 그 키를 치우기 전에는 이 방의 강퇴가 하나도 성립하지 않는다.
            //
            // 읽기 경로가 이미 같은 판별을 쓴다. 거기서는 fail-open으로 흡수해 로그 등급만 올리지만
            // 여기서는 흡수할 것이 없다 — 등록하지 못한 강퇴는 일어나지 않은 강퇴다.
            if (RedisWrongType.matches(e)) {
                throw new KickStoreCorruptedException(key, e);
            }
            throw e;
        }
    }
}
