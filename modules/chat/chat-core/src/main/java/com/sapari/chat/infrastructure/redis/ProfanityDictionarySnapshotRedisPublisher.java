package com.sapari.chat.infrastructure.redis;

import java.util.List;
import java.util.OptionalLong;
import java.util.Set;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import com.sapari.chat.application.port.ProfanityDictionarySnapshotPublisher;

import lombok.RequiredArgsConstructor;

/**
 * {@link ProfanityDictionarySnapshotPublisher} 구현 — 블로킹. live-app 이 {@code @Bean} 으로 세운다.
 *
 * <p><b>스테레오타입을 붙이지 않는다.</b> chat-core 를 리액티브 앱과 블로킹 앱이 함께 의존하고,
 * streaming-app 은 {@code com.sapari.chat} 을 스캔하므로 붙이면 거기서도 빈이 만들어진다 —
 * 그 앱은 정본을 읽을 수 없으니 발행할 것도 없다.
 */
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
     * <p>{@code PUBLISH} 를 Lua 안에서 부르는 것은 실측으로 확인했다(Redis 7.4.12, 반환 정상).
     */
    private static final RedisScript<Long> PUBLISH_SNAPSHOT = RedisScript.of("""
            local counter = 0
            if redis.call('TYPE', KEYS[2])['ok'] == 'string' then
                counter = tonumber(redis.call('GET', KEYS[2])) or 0
            end
            local head = 0
            if redis.call('TYPE', KEYS[1])['ok'] == 'string' then
                head = tonumber(string.match(redis.call('GET', KEYS[1]), '^(%d+)')) or 0
            end
            local current = counter
            if head > current then
                current = head
            end
            if current ~= tonumber(ARGV[1]) then
                return -1
            end
            local version = current + 1
            redis.call('SET', KEYS[2], version)
            redis.call('SET', KEYS[1], version .. '\\n' .. ARGV[2])
            redis.call('PUBLISH', ARGV[3], version)
            return version
            """, Long.class);

    /** 조건이 깨졌다는 신호. 스크립트가 쓰기 전에 돌려주는 값이다. */
    private static final long CAS_FAILED = -1L;

    private final StringRedisTemplate redisTemplate;

    /**
     * 조건 비교의 기준 — <b>스크립트와 같은 규칙</b>으로 잡는다(두 키의 큰 쪽).
     *
     * <p>여기와 스크립트가 다른 규칙을 쓰면 평시에도 조건이 어긋나 재시도만 소진한다. 그리고
     * 버전 키만 보던 때가 ABA 였다 — 그 벡터와 남는 축은 {@code PUBLISH_SNAPSHOT} javadoc 에 있다.
     */
    @Override
    public long currentVersion() {
        long head = snapshotHeadVersion();
        String raw;
        try {
            raw = redisTemplate.opsForValue().get(ChatRedisKeys.dictVersion());
        } catch (RuntimeException e) {
            if (RedisWrongType.matches(e)) {
                // 버전 키가 다른 타입이다. 비숫자와 같은 상태이고 복구도 같다(키 삭제) —
                // 다만 그 안내가 WRONGTYPE 으로 가려지지 않게 여기서 번역한다.
                throw new IllegalStateException(
                        "사전 버전 키가 우리 타입이 아니다 — 조건부 발행을 할 수 없다. 이 키("
                                + ChatRedisKeys.dictVersion()
                                + ")를 지우면 다음 발행이 버전을 다시 세운다.", e);
            }
            throw e;
        }
        if (raw == null) {
            // 버전 키가 없다. 본문이 남아 있으면 그 머리가 기준이고, 둘 다 없으면 0 —
            // 그 0 만이 "한 번도 발행되지 않았다" 다.
            return head;
        }
        try {
            return Math.max(head, Long.parseLong(raw.strip()));
        } catch (NumberFormatException e) {
            // 값을 싣지 않는다 — 신뢰경계 밖 문자열이라 개행 삽입으로 로그를 위조할 수 있다.
            // 여기서 던지는 쪽이 낫다: 조건 비교가 영원히 어긋나 재시도만 소진하는 것보다
            // 당직자에게 원인이 바로 간다.
            //
            // ⚠️ 본문 키와 달리 이 키에는 "다음 발행이 덮어 고친다" 가 없다 — 조건 비교의
            // 기준이라 깨지면 모든 발행이 여기서 막힌다. 그래서 복구를 메시지에 적는다:
            // <이 키를 지우면> currentVersion() 이 0 을 돌려주고 다음 발행이 v1 을 세운다.
            // 받는 쪽이 뒤로 간 버전도 적용하므로(ProfanityDictionaryRefreshHandler.apply)
            // 함대가 그 v1 을 집어 간다 — 그 처리가 없던 동안에는 이 키를 지워도 전 Pod 가
            // 더 낮은 버전을 조용히 버려서 복구가 안 됐다.
            throw new IllegalStateException(
                    "사전 버전 키가 숫자가 아니다 — 길이 " + raw.length()
                            + ". 조건부 발행을 할 수 없다. 이 키("
                            + ChatRedisKeys.dictVersion()
                            + ")를 지우면 다음 발행이 버전을 다시 세우고 함대가 그것을 집어 간다.", e);
        }
    }

    /**
     * 스냅샷 본문 머리의 버전. 본문이 없거나 머리가 숫자가 아니면 {@code 0} 이다.
     *
     * <p>본문이 깨져 있어도 여기서 던지지 않는다 — 발행은 그 본문을 <b>덮어</b> 고치는 쪽이라,
     * 읽기에서 막으면 복구 경로가 자기 자신에게 막힌다. 머리를 못 읽으면 기준이 버전 키로
     * 떨어지고, 그쪽도 없으면 0 이다.
     */
    private long snapshotHeadVersion() {
        String snapshot;
        try {
            snapshot = redisTemplate.opsForValue().get(ChatRedisKeys.dictSnapshot());
        } catch (RuntimeException e) {
            if (RedisWrongType.matches(e)) {
                // 타입이 오염된 키는 기준에 기여하지 않는다 — 발행이 그대로 덮어 고친다.
                return 0L;
            }
            throw e;
        }
        if (snapshot == null) {
            return 0L;
        }
        int newline = snapshot.indexOf('\n');
        String head = newline < 0 ? snapshot : snapshot.substring(0, newline);
        try {
            return Long.parseLong(head.strip());
        } catch (NumberFormatException e) {
            return 0L;
        }
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
        return version == CAS_FAILED ? OptionalLong.empty() : OptionalLong.of(version);
    }
}
