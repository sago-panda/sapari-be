package com.sapari.chat.infrastructure.redis;

import java.util.List;
import java.util.OptionalLong;
import java.util.Set;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import com.sapari.chat.application.port.ProfanityDictionarySnapshotPublisher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link ProfanityDictionarySnapshotPublisher} 구현 — 블로킹. live-app 이 {@code @Bean} 으로 세운다.
 *
 * <p><b>스테레오타입을 붙이지 않는다.</b> chat-core 를 리액티브 앱과 블로킹 앱이 함께 의존하고,
 * streaming-app 은 {@code com.sapari.chat} 을 스캔하므로 붙이면 거기서도 빈이 만들어진다 —
 * 그 앱은 정본을 읽을 수 없으니 발행할 것도 없다.
 */
@Slf4j
@RequiredArgsConstructor
public class ProfanityDictionarySnapshotRedisPublisher implements ProfanityDictionarySnapshotPublisher {

    /**
     * 버전 전진 · 본문 쓰기 · 알림 발행을 <b>한 스크립트</b>로 하고, 그 전체에 <b>조건</b>을 건다.
     *
     * <p>셋을 나누면 셋 다 다른 실패 조합을 만든다. 버전만 오르고 본문이 안 써지면 받는 쪽의
     * 주기 확인이 "새 버전이 있다" 를 보고 옛 본문을 다시 읽어 <b>버전만 올려 둔다</b> — 그 뒤로
     * 진짜 변경이 와도 같은 버전이면 건너뛴다. 본문만 써지고 알림이 안 가면 전파가 주기 확인
     * 간격만큼 늦는다. 한 스크립트면 그 조합이 아예 없다.
     *
     * <p>⚠️ <b>한때 {@code INCR} 였고 그것이 결함이었다.</b> 이 스크립트가 닫는 것은 <i>전송
     * 구간</i>뿐이고 본문은 그 전에 호출자가 정본에서 읽은 값이다. 무조건 전진하면 나중에
     * 발행한 쪽이 버전을 이기면서 <b>더 낡은 본문</b>을 들고 올 수 있다(포트 javadoc 의 끼어들기).
     * 그래서 {@code INCR} 을 <b>"내가 집어 둔 번호가 아직 그대로일 때만 +1"</b> 로 바꿨다.
     *
     * <p>실패는 {@code -1} 이다. 그때 <b>아무것도 쓰지 않는다</b> — 번호도 전진하지 않으므로
     * 버전 키와 스냅샷 본문의 버전이 어긋나 보일 일이 없다({@code ChatRedisKeys.dictVersion}
     * javadoc 의 그 불변식). {@code INCR} 뒤에 조건을 걸었다면 실패한 시도마다 번호가 타고
     * 받는 쪽이 매 주기마다 헛읽기를 하게 된다.
     *
     * <p>⚠️ <b>기준을 두 키의 큰 쪽으로 잡는다 — 한때 버전 키만 봤고 그것이 ABA 였다.</b>
     * {@code GET} 이 없는 키에 {@code false} 를 주므로 {@code or '0'} 으로 떨어뜨리면
     * "한 번도 발행 안 됨" 과 "버전 키가 사라짐" 이 <b>같은 토큰(0)</b>이 된다. 토큰이 유일하지
     * 않으면 조건이 지키는 것이 없다 — 실측(2026-11-05, redis 7.4.12):
     * <pre>
     *   B publish(expected=0)   → 1   본문 {X,Y}
     *   -- 버전 키 축출 --
     *   A publish(expected=0)   → 1   본문 {X}      ← 번호 재사용 + 낡은 본문
     *   대조군(축출 없음)        → -1  본문 {X,Y} 유지
     * </pre>
     * 그리고 성공을 돌려주므로 재시도도 없고, v1 을 이미 적용한 Pod 은 버전이 같아 다시 읽지
     * 않는다 — 함대가 갈린 채 다음 쓰기·부팅까지 남는다. 도달 경로가 {@code allkeys-lru} 축출과
     * <b>이 클래스가 안내하는 복구 절차(버전 키 삭제)</b> 자신이었다.
     *
     * <p>큰 쪽을 쓰면 한 키가 사라져도 다른 키가 토큰을 떨어뜨리지 않는다. <b>남는 축이 하나
     * 있다</b>: 두 키가 <i>함께</i> 사라지면 기준이 0 으로 돌아가므로 그때 낡은 발행자가 통과한다.
     * 그 상태에서는 덮을 새 본문도 이미 없어서 피해가 "v1 을 들고 있던 Pod 이 다시 읽지 않는다"
     * 로 좁아지지만, <b>영구 저장이 아닌 곳에서는 "한 번도 없었다" 와 "있었는데 사라졌다" 를
     * 구분할 수 없어</b> 이 축은 Redis 만으로 닫히지 않는다. 완전히 닫으려면 버전이 정본(DB)에서
     * 와야 한다 — 그쪽은 삭제돼도 번호가 재사용되지 않는다.
     *
     * <p>⚠️ 알림 본문은 <b>버전 하나</b>다. 사전 내용을 실으면 Pub/Sub 이 비영속이라 놓친 Pod 이
     * 영구히 다른 사전으로 집행한다. 버전만 실으면 그 Pod 이 주기 확인에서 스스로 복구한다 —
     * 알림은 빠른 길이고 정본은 스냅샷이다({@code ChatRedisKeys.dictChanged} javadoc).
     *
     * <p>{@code SET} 이라 이전 값의 타입을 가리지 않고 덮는다 — 누군가 이 키를 다른 타입으로
     * 써 놨어도 다음 발행이 고친다.
     *
     * <p>⚠️ 그래서 <b>읽기 쪽에 {@code TYPE} 가드가 필요하다.</b> 기준을 본문 머리에서도 잡게
     * 하면서 {@code GET} 을 그냥 걸었더니 타입이 오염된 키에서 {@code WRONGTYPE} 으로 터져
     * <b>모든 발행이 막혔다</b> — "다음 발행이 덮어 고친다" 는 이 클래스의 복구 성질을 읽기 한
     * 줄이 없애 버렸다(기존 테스트가 그 자리에서 깨졌다). 타입이 문자열이 아니면 그 키는 기준에
     * 기여하지 않고({@code 0}) 쓰기가 그대로 덮는다 — 복구가 유지된다. 강퇴 명단이 {@code TYPE}
     * 을 먼저 보는 것과 같은 이유이고, 그쪽은 더하기라 고칠 수 없었다는 점만 다르다.
     *
     * <p>⚠️ <b>그 성질을 두 키에 같이 준다.</b> 한때 이 문단이 키를 구분하지 않고 단정했는데
     * 자바 쪽은 <b>버전 키</b>에서 던지고 있었다 — {@code LPUSH} 한 줄로 모든 ADMIN 쓰기와 부팅
     * 발행이 500 이고, 스크립트는 그 키를 덮어 고칠 수 있는데 그 스크립트가 애초에 돌지 못했다.
     * 지금은 양쪽 모두 "기여하지 않는다(0)" 이고, 못 읽은 키는 WARN 한 줄로 남긴다.
     *
     * <p><b>머리 문법은 {@code ProfanityDictionarySnapshotCodec.readVersionToken} 한 곳에
     * 있고 아래 {@code token()} 이 그 거울이다.</b> 같은 문자열을 Lua 와 자바가 각자 읽는데
     * 규칙이 달랐고(열 벡터 중 여섯 갈림, 근거는 그 메서드 javadoc) 갈리면 조건이 평시에도
     * 어긋나 재시도만 소진한 뒤 엉뚱한 키를 가리키는 메시지를 던졌다. 한쪽을 고치면 다른 쪽도
     * 고친다 — 둘이 같다는 것을 테스트가 같은 벡터로 못 박는다.
     *
     * <p>{@code PUBLISH} 를 Lua 안에서 부르는 것은 실측으로 확인했다(Redis 7.4.12, 반환 정상).
     *
     * 스크립트 본문 — <b>자리수 상한을 상수에서 끼워 넣는다.</b>
     *
     * <p>한때 Lua 쪽 15 가 리터럴이었고 자바 쪽은 상수였다. 바로 위 javadoc 이 "한쪽을 고치면
     * 다른 쪽도 고친다" 를 적어 두고 그 하중을 <b>주석에</b> 걸고 있었던 자리다 — 실제로 상한을
     * 18 → 15 로 내리는 변경에서 설명문 세 자리가 18 에 남았다. 끼워 넣으면 그 축이 구조로 닫힌다.
     *
     * <p>{@code %%d} 는 Lua 의 {@code string.format} 으로 가야 하는 리터럴이라 두 번 쓴다 —
     * {@link String#formatted} 가 먼저 한 겹 벗긴다.
     */
    private static final String PUBLISH_SCRIPT = """
            -- 머리 문법의 거울 — ProfanityDictionarySnapshotCodec.readVersionToken 과 같아야 한다.
            -- 숫자 1~%d자로 시작하고 그 뒤는 끝이거나 개행. 그 밖은 0("모른다").
            local function token(raw)
                if type(raw) ~= 'string' then return 0 end
                local digits = string.match(raw, '^(%%d+)')
                if digits == nil or #digits > %d then return 0 end
                local tail = string.sub(raw, #digits + 1, #digits + 1)
                if tail ~= '' and tail ~= '\\n' then return 0 end
                return tonumber(digits)
            end
            local counter = 0
            if redis.call('TYPE', KEYS[2])['ok'] == 'string' then
                counter = token(redis.call('GET', KEYS[2]))
            end
            local head = 0
            if redis.call('TYPE', KEYS[1])['ok'] == 'string' then
                head = token(redis.call('GET', KEYS[1]))
            end
            local current = counter
            if head > current then
                current = head
            end
            if current ~= tonumber(ARGV[1]) then
                return -1
            end
            local version = current + 1
            -- ⚠️ 쓰는 포맷도 하나여야 한다. KEYS[2] 는 Redis 의 숫자 인자 변환(정확)을 타는데
            -- KEYS[1] 은 '..' 를 타고 그것은 %%.14g 다 — 값이 15자리가 되면 1e+14 가 키에 써지고
            -- 매 발행이 같은 머리를 다시 쓰니 자기치유가 없다(실측 3회차까지 1e+14 고정).
            local text = string.format('%%d', version)
            -- ⚠️ 상한은 토큰이 아니라 <후계자>에 걸어야 닫힌다. 토큰 상한 d 는 10^d-1 을 받고
            -- 그 +1 은 d+1 자리라 어떤 d 에서도 문법 밖이다. 쓰고 나면 두 키가 다 문법 밖이 되어
            -- 토큰이 0 으로 떨어지고 낡은 발행자가 expected=0 으로 이긴다(ABA 재개방, 실측).
            -- 그래서 쓰기 전에 거부한다 — 번호 공간 고갈은 조용히 되돌아가는 것보다 멈추는 쪽이다.
            if #text > %d then
                return -2
            end
            redis.call('SET', KEYS[2], text)
            redis.call('SET', KEYS[1], text .. '\\n' .. ARGV[2])
            redis.call('PUBLISH', ARGV[3], text)
            return version
            """;

    /** 위 본문에 상한을 끼워 컴파일한 스크립트. 설계 근거는 {@link #PUBLISH_SCRIPT}. */
    private static final RedisScript<Long> PUBLISH_SNAPSHOT = RedisScript.of(
            PUBLISH_SCRIPT.formatted(
                    ProfanityDictionarySnapshotCodec.MAX_VERSION_DIGITS,
                    ProfanityDictionarySnapshotCodec.MAX_VERSION_DIGITS,
                    ProfanityDictionarySnapshotCodec.MAX_VERSION_DIGITS),
            Long.class);

    /** 조건이 깨졌다는 신호. 스크립트가 쓰기 전에 돌려주는 값이다. */
    private static final long CAS_FAILED = -1L;

    /**
     * 번호 공간이 고갈됐다는 신호 — 스크립트가 <b>쓰기 전에</b> 돌려준다.
     *
     * <p>{@code CAS_FAILED} 와 가르는 이유는 복구가 다르기 때문이다. 조건 실패는 재시도로 낫고,
     * 이쪽은 재시도로 낫지 않는다 — 다음 버전이 문법 밖이라 쓰는 순간 두 키가 못 읽히는 값이
     * 되고 토큰이 0 으로 떨어져 낡은 발행자가 이긴다(ABA 재개방). 사람이 두 키를 의도적으로
     * 낮은 값으로 되돌려야 한다.
     */
    private static final long VERSION_SPACE_EXHAUSTED = -2L;

    private final StringRedisTemplate redisTemplate;

    /**
     * 조건 비교의 기준 — <b>스크립트와 같은 문법</b>으로 두 키를 읽어 큰 쪽을 집는다.
     *
     * <p>여기와 스크립트가 다른 규칙을 쓰면 평시에도 조건이 어긋나 재시도만 소진한다. 그래서
     * 문법이 {@link ProfanityDictionarySnapshotCodec#readVersionToken} 한 곳에 있고 Lua 가 그
     * 거울이다. 버전 키만 보던 때가 ABA 였다 — 그 벡터와 남는 축은 {@code PUBLISH_SNAPSHOT}
     * javadoc 에 있다.
     *
     * <p><b>못 읽은 키는 던지지 않고 기여하지 않는다(0).</b> 한때 버전 키에서 던졌는데, 그러면
     * 스크립트가 그 키를 덮어 고칠 수 있는데도 <b>그 스크립트가 돌지 못했다</b> — 숫자 아닌 값
     * 하나 또는 {@code LPUSH} 한 줄로 모든 발행이 500 이고 복구가 수동 {@code DEL} 뿐이었다.
     * 두 키가 다 안 읽히면 토큰이 0 이고, 그때 발행이 v1 을 세워 양쪽을 다시 쓴다.
     *
     * <p>못 읽었다는 사실은 WARN 으로 남긴다 — 조용하면 ABA 가 다시 열린 것(토큰이 한 키로
     * 떨어진 상태)을 아무도 모른다. <b>값은 싣지 않는다</b>: 신뢰경계 밖 문자열이라 개행 삽입으로
     * 로그를 위조할 수 있고, 길이만으로 "빈 값" 과 "긴 쓰레기" 가 갈린다.
     */
    @Override
    public long currentVersion() {
        long head = tokenOf(ChatRedisKeys.dictSnapshot(), "스냅샷 본문");
        long counter = tokenOf(ChatRedisKeys.dictVersion(), "버전 키");
        return Math.max(head, counter);
    }

    /**
     * 키 하나의 토큰 기여분. 없거나 타입이 다르거나 문법에 안 맞으면 {@code 0} 이다.
     *
     * <p>세 경우를 가르지 않는 것이 의도다 — 토큰에 대해서는 전부 "이 키는 기준을 못 준다" 로
     * 같고, 셋 다 다음 발행이 {@code SET} 으로 덮어 고친다. 가르는 것은 로그뿐이다.
     */
    private long tokenOf(String key, String what) {
        String raw;
        try {
            raw = redisTemplate.opsForValue().get(key);
        } catch (RuntimeException e) {
            if (RedisWrongType.matches(e)) {
                log.warn("사전 {}의 타입이 우리 것이 아니다 — 조건 비교에 기여하지 않는다(0). "
                                + "다음 발행이 이 키를 덮어 고친다. key={}", what, key);
                return 0L;
            }
            throw e;
        }
        if (raw == null) {
            return 0L;
        }
        OptionalLong token = ProfanityDictionarySnapshotCodec.readVersionToken(raw);
        if (token.isEmpty()) {
            log.warn("사전 {}의 머리를 읽을 수 없다 — 조건 비교에 기여하지 않는다(0). 다음 발행이 "
                            + "이 키를 덮어 고친다. key={} 길이={}", what, key, raw.length());
            return 0L;
        }
        return token.getAsLong();
    }

    @Override
    public OptionalLong publishIfUnchanged(long expectedVersion, Set<String> words,
            Set<String> whitelist) {
        Long version = redisTemplate.execute(PUBLISH_SNAPSHOT,
                List.of(ChatRedisKeys.dictSnapshot(), ChatRedisKeys.dictVersion()),
                String.valueOf(expectedVersion),
                ProfanityDictionarySnapshotCodec.encodeBody(words, whitelist),
                ChatRedisKeys.dictChanged());
        if (version == null) {
            // 스크립트가 항상 수를 돌려주므로 여기 오면 드라이버 쪽이 어긋난 것이다. 성공으로
            // 뭉개면 호출자가 "발행됐다" 로 로그를 남기고 함대에 안 깔린 것을 모른다.
            throw new IllegalStateException(
                    "사전 스냅샷 발행이 버전을 돌려주지 않았다 — 발행 여부를 확인할 수 없다.");
        }
        if (version == VERSION_SPACE_EXHAUSTED) {
            throw new IllegalStateException(
                    "사전 버전 번호 공간이 고갈됐다 — 다음 버전이 " + (
                            ProfanityDictionarySnapshotCodec.MAX_VERSION_DIGITS + 1)
                            + "자리가 되어 문법 밖이다. 쓰지 않았다(쓰면 두 키가 못 읽히는 값이 "
                            + "되고 토큰이 0 으로 떨어져 낡은 발행자가 이긴다). 유기적 증가로는 "
                            + "도달하지 않으므로 누가 버전 키 또는 스냅샷 머리에 큰 수를 넣은 "
                            + "것이다 — 두 키를 의도적으로 낮은 값으로 되돌려야 한다.");
        }
        return version == CAS_FAILED ? OptionalLong.empty() : OptionalLong.of(version);
    }
}
