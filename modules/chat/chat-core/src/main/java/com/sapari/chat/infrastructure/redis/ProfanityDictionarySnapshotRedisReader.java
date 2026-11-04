package com.sapari.chat.infrastructure.redis;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Repository;

import com.sapari.chat.application.port.ProfanityDictionarySnapshot;
import com.sapari.chat.application.port.ProfanityDictionarySnapshotReader;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * {@link ProfanityDictionarySnapshotReader} 구현 — 리액티브. streaming-app 이 스캔으로 얻는다.
 *
 * <p>읽기가 {@code GET} 한 번이다. Lua 가 필요 없는 것이 키를 하나로 둔 보상이다 — 버전과 두
 * 집합이 같은 값 안에 있으므로 "반쪽만 새것" 인 조합을 볼 경로가 없다
 * ({@code ChatRedisKeys.dictSnapshot} javadoc).
 *
 * <p><b>WRONGTYPE 을 따로 다루지 않는다.</b> 쓰는 쪽이 {@code SET} 이라 타입을 가리지 않고
 * 덮으므로, 이 키가 다른 타입이면 누군가 밖에서 써 놓은 것이고 다음 발행이 고친다. 그 사이의
 * 읽기는 실패로 올라가고 호출자가 사전 교체를 포기한다 — 강퇴 명단처럼 이쪽에서 고칠 수 있는
 * 결함이 아니다(그쪽은 더하기라 오염된 키에 애초에 들어가지 못해 복구 경로가 필요했다).
 */
@Repository
@RequiredArgsConstructor
public class ProfanityDictionarySnapshotRedisReader implements ProfanityDictionarySnapshotReader {

    private final ReactiveStringRedisTemplate redisTemplate;

    @Override
    public Mono<ProfanityDictionarySnapshot> read() {
        return redisTemplate.opsForValue().get(ChatRedisKeys.dictSnapshot())
                .map(ProfanityDictionarySnapshotCodec::decode);
    }

    @Override
    public Mono<Long> readVersion() {
        return redisTemplate.opsForValue().get(ChatRedisKeys.dictVersion())
                .map(ProfanityDictionarySnapshotRedisReader::parseVersion);
    }

    /**
     * 값을 실패 메시지에 싣지 않는다 — 신뢰경계 밖 문자열이라 개행 삽입으로 로그를 위조할 수 있다.
     * 길이는 남긴다: "빈 값" 과 "숫자가 아닌 긴 값" 은 원인이 다르다.
     */
    private static long parseVersion(String raw) {
        try {
            return Long.parseLong(raw.strip());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "사전 버전이 숫자가 아니다 — 길이 " + raw.length() + ".", e);
        }
    }
}
