package com.sapari.chat.infrastructure.redis;

import java.util.List;
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
     * 버전 증가 · 본문 쓰기 · 알림 발행을 <b>한 스크립트</b>로 한다.
     *
     * <p>셋을 나누면 셋 다 다른 실패 조합을 만든다. 버전만 오르고 본문이 안 써지면 받는 쪽의
     * 주기 확인이 "새 버전이 있다" 를 보고 옛 본문을 다시 읽어 <b>버전만 올려 둔다</b> — 그 뒤로
     * 진짜 변경이 와도 같은 버전이면 건너뛴다. 본문만 써지고 알림이 안 가면 전파가 주기 확인
     * 간격만큼 늦는다. 한 스크립트면 그 조합이 아예 없다.
     *
     * <p>버전을 클라이언트가 정하지 않고 {@code INCR} 로 받는 것이 단조성의 근거다 — live-app Pod
     * 가 둘이면 각자 정한 번호가 겹치거나 뒤로 갈 수 있고, 그러면 받는 쪽의 "큰 것만 적용" 이
     * 거짓을 지킨다.
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
            local version = redis.call('INCR', KEYS[2])
            redis.call('SET', KEYS[1], version .. '\\n' .. ARGV[1])
            redis.call('PUBLISH', ARGV[2], version)
            return version
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    @Override
    public long publish(Set<String> words, Set<String> whitelist) {
        Long version = redisTemplate.execute(PUBLISH_SNAPSHOT,
                List.of(ChatRedisKeys.dictSnapshot(), ChatRedisKeys.dictVersion()),
                ProfanityDictionarySnapshotCodec.encodeBody(words, whitelist),
                ChatRedisKeys.dictChanged());
        if (version == null) {
            // 스크립트가 항상 수를 돌려주므로 여기 오면 드라이버 쪽이 어긋난 것이다. 0 으로
            // 뭉개면 호출자가 "발행됐다" 로 로그를 남기고 함대에 안 깔린 것을 모른다.
            throw new IllegalStateException(
                    "사전 스냅샷 발행이 버전을 돌려주지 않았다 — 발행 여부를 확인할 수 없다.");
        }
        return version;
    }
}
