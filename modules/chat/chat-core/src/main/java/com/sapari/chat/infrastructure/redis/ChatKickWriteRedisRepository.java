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

    /** 한 {@code SADD} 에 넘기는 멤버 수. Lua 스택 한계(약 8,000)보다 한참 아래면 값 자체는 무관하다. */
    static final int SADD_CHUNK = 500;

    /**
     * 지우고 다시 채우고 만료를 떼는 것이 <b>한 번의 실행</b>이다.
     *
     * <p><b>⚠️ {@code DEL} 은 오염된 키에만 쓴다 — 무조건 지우면 동시 강퇴를 영구히 삼킨다.</b>
     * 처음에 {@code DEL} 을 맨 앞에 뒀는데, 강퇴가 DB-first 라(로그 커밋 확정 → {@code SADD})
     * 이런 끼어들기가 열렸다:
     * <pre>
     *   A(재적재)  SELECT 정본 → [u1]        (u3 의 로그 행은 아직 미커밋)
     *   B(강퇴 u3)                            u3 커밋 → SADD u3
     *   A          DEL + SADD [u1]           → u3 가 집행 명단에서 사라진다(정본에는 있다)
     * </pre>
     * 204 를 받은 강퇴가 조용히 무효화되고, 읽기 경로가 fail-open 이라 아무도 막히지 않으며,
     * 재시도 경로가 없어 <b>영구</b>다. 복구하려 부른 조치가 집행을 지운다.
     *
     * <p><b>그래서 건강한 키에는 더하기만 한다.</b> 그게 충분한 이유는 집합의 방향이 한쪽이기
     * 때문이다 — 멤버를 넣는 것은 강퇴뿐이고 강퇴는 <b>로그를 먼저 커밋</b>하므로,
     * 앱 경로에서 {@code SET ⊆ 정본} 이 늘 참이다. 반대(정본에 없는데 SET 에 있음)는 만들어지지
     * 않는다. 그러니 재적재가 지워야 할 것은 없고, 모자란 것을 채우는 것으로 족하다.
     * 더하기는 동시 강퇴의 {@code SADD} 를 절대 덮지 않는다.
     *
     * <p>오염된 키에서만 {@code DEL} 이 필요하다 — {@code SADD} 는 타입이 틀린 키에 아예 들어가지
     * 못하고, 그게 이 메서드가 닫으려는 가장 나쁜 경우다. 그 분기에서 잃는 것도 없다: 그 키의
     * 내용은 애초에 우리가 읽을 수 없는 값이다. 그래서 {@code reconcile} 은 WRONGTYPE 을 갈라
     * 던지지 않는다 — 이 경로에서 그것은 고치려는 대상이지 보고할 실패가 아니다.
     *
     * <p><b>{@code PERSIST} 가 돌아왔다.</b> {@code DEL} 이 맨 앞이던 판에서는 죽은 코드였고
     * (키를 지우면 만료도 사라진다) 되돌림 확인으로 그걸 재서 지웠다. 더하기로 바뀐 지금은
     * {@code SADD} 가 기존 만료를 건드리지 않으므로 다시 <b>일한다</b> — 회수용 만료가 붙은 키에
     * 더하면 되살린 명단이 그 시각에 통째로 사라진다. 같은 두 줄이 설계가 바뀌면 보장과 장식을
     * 왕복한다는 증거라 그 역사를 남긴다.
     *
     * <p>{@code ARGV} 가 비면 루프가 한 번도 돌지 않는다. Redis 의 {@code SADD} 는 멤버가 하나는
     * 있어야 하고, 더 중요하게는 <b>멤버 없는 집합이 존재하지 않는다</b> — 정본이 비었으면 키가
     * 없는 상태가 올바른 결과다.
     *
     * <p><b>⚠️ {@code unpack(ARGV)} 를 통째로 쓰지 않는다 — 그 판은 파괴적으로 실패했다.</b>
     * Lua 스택 한계(약 8,000)를 넘으면 {@code unpack} 이 던지는데, 그 시점은 {@code DEL} 이
     * <b>이미 실행된 뒤</b>다. Redis 스크립트는 중간 실패를 되감지 않으므로(다른 명령이 끼어들지
     * 않을 뿐 이미 실행된 효과는 남는다) 결과가 <b>"명단을 지우고 복원하지 않은 상태"</b> — 이
     * 포트가 한 실행으로 묶은 이유로 든 바로 그 실패다. 실측(redis:7.4, 기존 2명 + 9,000명 요청):
     * {@code -ERR too many results to unpack} 뒤 {@code SCARD 0} · {@code TYPE none}.
     * <b>복구하려 부른 조치가 그 방을 날린다.</b>
     *
     * <p>그래서 {@code unpack} 에 <b>범위를 줘서 쪼갠다</b>. 한계가 사라지고 한 실행도 유지된다
     * (실측: 같은 환경에서 9,000명·50,000명 모두 전량 복원, 기존 명단 손실 0). 입력이 Postgres 에서
     * 오는 <b>무계</b> 값이므로 "오늘은 그만큼 없다" 에 기대지 않는다 — 기대면 그 날이 왔을 때
     * 알게 되고, 알게 되는 방식이 방 하나가 비는 것이다.
     *
     * <p><b>고치기 전의 타입을 함께 돌려준다.</b> {@code DEL} 은 타입과 무관하게 성공하므로 그 뒤에는
     * "오염돼 있었다" 를 알 길이 없다. 그런데 그 사실이 <b>집행이 꺼져 있었다는 직접 증거</b>이고,
     * 이 조치를 ADMIN 전용으로 둔 근거가 가청성이었다 — 복원 수만 돌려주면 가장 센 신호가 사라진다.
     * 그래서 {@code TYPE} 을 {@code DEL} <b>앞에서</b> 읽는다.
     */
    private static final RedisScript<Long> RECONCILE_KICKED = RedisScript.of("""
            local wasCorrupted = 0
            local before = redis.call('TYPE', KEYS[1])['ok']
            if before ~= 'none' and before ~= 'set' then
                wasCorrupted = 1
                redis.call('DEL', KEYS[1])
            end
            local chunk = tonumber(ARGV[1])
            for i = 2, #ARGV, chunk do
                redis.call('SADD', KEYS[1], unpack(ARGV, i, math.min(i + chunk - 1, #ARGV)))
            end
            if #ARGV > 1 then
                redis.call('PERSIST', KEYS[1])
            end
            return wasCorrupted
            """, Long.class);

    /**
     * @return 고치기 전 키가 <b>우리 타입이 아니었는가</b>. 참이면 그 방의 강퇴 집행이 꺼져 있었다는
     *         직접 증거다 — 호출자가 그 사실을 남겨야 한다
     */
    @Override
    public boolean reconcile(UUID roomId, java.util.Collection<UUID> kickedUserIds) {
        String key = ChatRedisKeys.kicked(roomId);
        // ARGV[1] 이 청크 크기이고 멤버는 2번부터다 — 스크립트가 상수를 몰라야 그 값을 Java 쪽에서
        // 한 자리로 쥔다.
        List<Object> args = new java.util.ArrayList<>();
        args.add(String.valueOf(SADD_CHUNK));
        kickedUserIds.forEach(id -> args.add(id.toString()));

        // 실패는 그대로 던진다 — WRONGTYPE 도 갈라내지 않는다. DEL 이 그것을 고치므로 이 경로에서는
        // 오염이 실패가 아니고, 갈라 던지면 "복구하려 불렀는데 복구 대상이라며 거부" 가 된다.
        // 다만 사실은 잃지 않는다 — 스크립트가 고치기 전 타입을 보고 돌려준다.
        Long corrupted = redisTemplate.execute(RECONCILE_KICKED, List.of(key), args.toArray());
        return corrupted != null && corrupted == 1L;
    }
}
