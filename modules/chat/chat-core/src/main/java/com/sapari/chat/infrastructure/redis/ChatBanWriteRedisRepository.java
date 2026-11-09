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
 * <p><b>늘리기 전용은 적용끼리의 경쟁만 닫는다.</b> 해제와 적용이 엇갈리면 다르다 — 정본 쪽 잠금이
 * 둘을 줄 세워도 미러 반영은 커밋 뒤 잠금 밖이라, 해제의 {@code DEL} 뒤에 먼저 커밋된 적용이 도착하면
 * 키가 없으므로 무조건 쓰인다. 그래서 모든 쓰기가 잠금 안에서 집은 변경 번호를 싣고, 번호 키
 * ({@code chat:ban:ver:{userId}})에 본 것 중 가장 큰 번호를 두어 그 이하를 거절한다. 실제 Redis 에서
 * "해제 뒤 늦은 적용" 을 재현해 고정했다({@code ChatBanWriteRedisRepositoryTest}).
 *
 * <p><b>이 키에는 타입 충돌 위험이 없다.</b> 읽기가 {@code EXISTS}라 타입을 보지 않기 때문이다.
 * (쓰기가 덮어쓰기라서가 아니다 — 이제 쓰기는 짧은 값을 거절한다.) 강퇴 명단({@code SISMEMBER})이 WRONGTYPE에 노출돼 별도 예외로
 * 갈라야 했던 것과 다르므로 그 분류를 여기까지 넓히지 않는다.
 *
 * <p>⚠️ <b>{@code ban} 으로는 밴을 짧게 줄일 수 없다.</b> 관리자 감형 경로를 만들 때는 키 삭제 후 재설정이나
 * 별도 강제 쓰기가 <b>같은 변경에</b> 함께 와야 한다. 해제(행 삭제)는 {@code releaseMirror} 가 지운다.
 *
 * <p><b>그때의 순서는 정본 → 미러다.</b> 중간에 끊기면 "정본은 풀렸는데 미러가 남아 있다" = 과다 차단으로
 * 기울고, 반대 순서(미러 먼저)의 위험은 "밴이 풀린다"가 아니라 <b>되살아나는 것</b>이다 —
 * 미러만 지우고 정본 삭제가 실패하면 남은 행이 다음 강퇴 때 활성 밴으로 잡혀 미러에 다시 써진다.
 * 정본을 먼저 지운 쪽의 잔여 상태(과다 차단)에는 그런 자기 복원 경로가 없다.
 *
 * <p><b>그리고 이 삭제는 정본 행의 존재를 묻지 않아야 한다.</b> 정본을 먼저 지웠으므로 재시도 때는 이미
 * 행이 없는데, 거기서 "지울 밴이 없다"로 끊으면 미러에 도달할 코드가 안 남는다. 없는 키 삭제는 no-op이라
 * 무조건 실행이 안전하다.
 *
 * <p>⚠️ 이 키는 영구 밴일 때 TTL이 없다. 기한부는 만료로 스스로 풀리지만 영구는 안 풀리므로, 해제에서
 * 이 삭제가 빠지면 사람이 손대기 전까지 영구히 막힌다.
 *
 * <p>정본도 이제 늘리기 전용이라({@code uk_chat_ban_user_id} + {@code ON CONFLICT DO UPDATE}) 두
 * 저장소가 같은 방향을 본다. 다만 규칙이 <b>같지는 않다</b> — 정본은 절대 시각을, 여기는 남은 TTL을
 * 비교한다. 같은 만료를 두 번 쓰면 정본은 0행이고 미러는 갱신이라, 어긋날 때 미러가 더 길다.
 * 그 방향이 안전한 쪽이다.
 */
@RequiredArgsConstructor
public class ChatBanWriteRedisRepository implements ChatBanWriteRepository {

    /** 값은 읽히지 않는다. 존재만이 의미다. */
    private static final String PRESENT = "1";

    /** 영구 밴을 나타내는 인자. 빈 문자열인 이유는 Lua에서 숫자와 갈라 보기 쉬워서다. */
    private static final String FOREVER = "";

    /**
     * 번호 키의 수명. 늦은 쓰기는 <b>같은 요청 안에서</b> 커밋과 Redis 호출 사이에 생기므로 요청 하나의
     * 수명만 넘기면 된다 — 하루는 그보다 몇 자릿수 길게 고른 값이지 측정값이 아니다. 번호 키가 먼저
     * 사라져도 다음 변경은 더 큰 번호를 들고 오므로 거절당하지 않는다.
     */
    static final Duration VERSION_TTL = Duration.ofDays(1);

    /**
     * 번호를 먼저 보고, 새것이면 더 긴 만료만 반영한다. 비교와 쓰기가 한 번의 실행 안에 있어
     * <b>도착 순서와 무관</b>하다.
     *
     * <p>번호 검사가 만료 비교보다 앞이다. 해제 뒤에 늦게 온 적용은 키가 없으므로({@code PTTL = -2})
     * 만료 비교만으로는 무조건 쓰인다 — 그게 이 스크립트가 막는 경쟁이다. 새 번호는 결과와 무관하게
     * 기록한다(본 것 중 가장 큰 번호가 기준이다).
     *
     * <p>{@code PTTL}은 키가 없으면 {@code -2}, 만료가 없으면 {@code -1}이다. 만료 없음은 "무한히 긴
     * 만료"라 어떤 값도 그것을 이기지 못한다. 이미 지난 만료는 아무것도 쓰지 않는다 — 음수 {@code PX}는
     * Redis가 거부하고, 정본에 없는 상태를 미러에만 남길 이유도 없다.
     *
     * <p>반환: {@code -1} 늦은 번호 · {@code 0} 무변화 · {@code 1} 연장 · {@code 2} 새로 씀.
     */
    private static final RedisScript<Long> VERSIONED_EXTEND_ONLY = RedisScript.of("""
            local seen = tonumber(redis.call('GET', KEYS[2]) or '0')
            if tonumber(ARGV[3]) <= seen then
                return -1
            end
            redis.call('SET', KEYS[2], ARGV[3], 'PX', ARGV[4])
            local ttl = redis.call('PTTL', KEYS[1])
            if ttl == -1 then
                return 0
            end
            local fresh = 1
            if ttl == -2 then
                fresh = 2
            end
            if ARGV[1] == '' then
                redis.call('SET', KEYS[1], ARGV[2])
                return fresh
            end
            local want = tonumber(ARGV[1])
            if want <= 0 then
                return 0
            end
            if ttl == -2 or want > ttl then
                redis.call('SET', KEYS[1], ARGV[2], 'PX', want)
                return fresh
            end
            return 0
            """, Long.class);

    /**
     * 번호가 새것일 때만 지운다. 만료 비교를 타지 않는 이유는 늘리기 전용 쓰기로는 어떤 값을 넣어도
     * 밴이 짧아지지 않아서다 — 해제는 쓰기가 아니라 삭제여야 한다.
     *
     * <p>반환: {@code 0} 늦은 번호 · {@code 1} 삭제를 실행함(키가 없었어도).
     */
    private static final RedisScript<Long> VERSIONED_DELETE = RedisScript.of("""
            local seen = tonumber(redis.call('GET', KEYS[2]) or '0')
            if tonumber(ARGV[1]) <= seen then
                return 0
            end
            redis.call('SET', KEYS[2], ARGV[1], 'PX', ARGV[2])
            redis.call('DEL', KEYS[1])
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    @Override
    public MirrorApply ban(UUID userId, Instant expiresAt, Instant now, long version) {
        String remaining = expiresAt == null
                ? FOREVER
                : String.valueOf(Duration.between(now, expiresAt).toMillis());
        Long result = redisTemplate.execute(VERSIONED_EXTEND_ONLY,
                List.of(ChatRedisKeys.banned(userId), ChatRedisKeys.banVersion(userId)),
                remaining, PRESENT, String.valueOf(version), String.valueOf(VERSION_TTL.toMillis()));
        return switch (result.intValue()) {
            case -1 -> MirrorApply.STALE;
            case 0 -> MirrorApply.UNCHANGED;
            case 1 -> MirrorApply.EXTENDED;
            case 2 -> MirrorApply.CREATED;
            default -> throw new IllegalStateException("밴 미러 스크립트가 모르는 값을 돌려줬다: " + result);
        };
    }

    /**
     * {@code EXISTS}로 먼저 묻지 않는다. 없는 키 삭제가 no-op 이므로 물어볼 이유가 없고, 묻는 순간
     * 왕복이 둘이 되며 그 사이가 새 레이스가 된다.
     */
    @Override
    public boolean releaseMirror(UUID userId, long version) {
        Long result = redisTemplate.execute(VERSIONED_DELETE,
                List.of(ChatRedisKeys.banned(userId), ChatRedisKeys.banVersion(userId)),
                String.valueOf(version), String.valueOf(VERSION_TTL.toMillis()));
        return result == 1L;
    }
}
