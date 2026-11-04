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
     * <p>⚠️ 알림 본문은 <b>버전 하나</b>다. 사전 내용을 실으면 Pub/Sub 이 비영속이라 놓친 Pod 이
     * 영구히 다른 사전으로 집행한다. 버전만 실으면 그 Pod 이 주기 확인에서 스스로 복구한다 —
     * 알림은 빠른 길이고 정본은 스냅샷이다({@code ChatRedisKeys.dictChanged} javadoc).
     *
     * <p>{@code SET} 이라 이전 값의 타입을 가리지 않고 덮는다 — 누군가 이 키를 다른 타입으로
     * 써 놨어도 다음 발행이 고친다. 강퇴 명단처럼 {@code TYPE} 을 먼저 보지 않는 이유가 그것이다
     * (그쪽은 더하기라 오염된 키에 들어가지 못한다).
     *
     * <p>{@code PUBLISH} 를 Lua 안에서 부르는 것은 실측으로 확인했다(Redis 7.4.12, 반환 정상).
     */
    private static final RedisScript<Long> PUBLISH_SNAPSHOT = RedisScript.of("""
            local current = tonumber(redis.call('GET', KEYS[2]) or '0')
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

    @Override
    public long currentVersion() {
        String raw = redisTemplate.opsForValue().get(ChatRedisKeys.dictVersion());
        if (raw == null) {
            // 한 번도 발행되지 않았다. 스크립트도 없는 키를 0 으로 읽으므로 둘이 맞는다.
            return 0L;
        }
        try {
            return Long.parseLong(raw.strip());
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
