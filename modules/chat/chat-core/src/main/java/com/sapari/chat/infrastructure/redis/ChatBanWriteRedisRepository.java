package com.sapari.chat.infrastructure.redis;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import com.sapari.chat.domain.repository.ChatBanWriteRepository;

import lombok.RequiredArgsConstructor;

/**
 * {@code chat:banned:{userId}} 쓰기 어댑터 — 만료는 값이 아니라 TTL이 표현하고, <b>줄어들지 않는다</b>.
 *
 * <p>읽는 쪽({@code ChatBanRedisRepository})이 키의 존재만 보므로 값은 자리를 채우는 문자 하나면 된다.
 * 값에 만료 시각을 담으면 그걸 해석하는 코드가 읽기·쓰기 양쪽에 생기고, 두 해석이 갈리는 순간 밴이
 * 조용히 풀리거나 풀리지 않는다.
 *
 * <p><b>왜 늘리기 전용인가.</b> 서로 다른 방에서 같은 사람을 동시에 강퇴하면 두 호출자가 각각 밴을 만들고
 * 각자 미러에 쓴다. 무조건 덮어쓰면 <b>나중에 도착한 쪽이 이기므로 짧은 TTL이 남을 수 있고</b>, 집행은
 * 미러가 하므로 그 사람은 정본에 한 달이 남아 있어도 일주일 뒤에 돌아온다. 실제로 재현됐다.
 *
 * <p>정본을 다시 읽어 가장 긴 것을 쓰는 방식도 시도했지만 <b>레이스를 좁힐 뿐 닫지 못한다</b> — 무엇을
 * 쓸지는 고쳐도 누가 마지막에 쓰는지는 그대로여서다. 순서에 기대지 않으려면 쓰기 자체가 순서와 무관해야
 * 하고, 그래서 비교와 쓰기를 한 번의 {@code EVAL}에 담는다.
 *
 * <p><b>이 키에는 타입 충돌 위험이 없다.</b> 쓰기가 {@code SET}이라 남이 먼저 쓴 값을 덮고, 읽기가
 * {@code EXISTS}라 타입을 보지 않는다. 강퇴 명단({@code SISMEMBER})이 WRONGTYPE에 노출돼 별도 예외로
 * 갈라야 했던 것과 다르므로 그 분류를 여기까지 넓히지 않는다.
 *
 * <p>⚠️ <b>이 포트로는 밴을 짧게 줄일 수 없다.</b> 관리자 감형 경로를 만들 때는 키 삭제 후 재설정이나
 * 별도 강제 쓰기가 <b>같은 변경에</b> 함께 와야 한다. 해제(행 삭제)도 미러 {@code DEL}이 함께 필요하다.
 */
@RequiredArgsConstructor
public class ChatBanWriteRedisRepository implements ChatBanWriteRepository {

    /** 값은 읽히지 않는다. 존재만이 의미다. */
    private static final String PRESENT = "1";

    /** 영구 밴을 나타내는 인자. 빈 문자열인 이유는 Lua에서 숫자와 갈라 보기 쉬워서다. */
    private static final String FOREVER = "";

    /**
     * 더 긴 만료만 반영한다. 비교와 쓰기가 한 번의 실행 안에 있어 <b>도착 순서와 무관</b>하다.
     *
     * <p>{@code PTTL}은 키가 없으면 {@code -2}, 만료가 없으면 {@code -1}이다. 그 둘을 가르는 것이 이
     * 스크립트의 전부다 — 만료 없음은 "무한히 긴 만료"라 어떤 값도 그것을 이기지 못한다.
     *
     * <p>이미 지난 만료는 아무것도 쓰지 않는다. 음수 {@code PX}는 Redis가 거부하고, 정본에 없는 상태를
     * 미러에만 남길 이유도 없다.
     */
    private static final RedisScript<Long> EXTEND_ONLY = RedisScript.of("""
            local ttl = redis.call('PTTL', KEYS[1])
            if ttl == -1 then
                return 0
            end
            if ARGV[1] == '' then
                redis.call('SET', KEYS[1], ARGV[2])
                return 1
            end
            local want = tonumber(ARGV[1])
            if want <= 0 then
                return 0
            end
            if ttl == -2 or want > ttl then
                redis.call('SET', KEYS[1], ARGV[2], 'PX', want)
                return 1
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    @Override
    public void ban(UUID userId, Instant expiresAt, Instant now) {
        String remaining = expiresAt == null
                ? FOREVER
                : String.valueOf(Duration.between(now, expiresAt).toMillis());
        redisTemplate.execute(EXTEND_ONLY, List.of(ChatRedisKeys.banned(userId)), remaining, PRESENT);
    }
}
