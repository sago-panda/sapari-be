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

    /**
     * ⚠️ <b>버전 키가 없으면 본문 머리로 떨어진다</b> — 한때 그 자리가 완전 무음이었다.
     *
     * <p>버전 키만 사라지면(두 키 모두 TTL 이 없지만 {@code allkeys-lru} 는 TTL 없는 키도
     * 축출한다) 이 메서드가 빈 {@code Mono} 를 주고, 호출자의 "스냅샷이 아직 없다" 분기는
     * <b>부팅 때만</b> 로그를 남긴다 — 주기 확인은 매분 아무 말 없이 지나간다. 그 사이 새로 뜨는
     * Pod 은 멀쩡한 스냅샷이 Redis 에 있는데도 번들로 집행한다(잃는 것 = 시드 이후의 ADMIN 델타).
     *
     * <p>도달 경로가 발행자가 안내하는 복구 절차(버전 키 삭제) 자신이기도 했다. 본문 머리로
     * 떨어지면 빈 {@code Mono} 는 <b>두 키가 함께 없을 때</b>, 즉 진짜로 한 번도 발행되지 않은
     * 상태에만 나온다.
     */
    @Override
    public Mono<Long> readVersion() {
        return token(ChatRedisKeys.dictVersion())
                .switchIfEmpty(Mono.defer(() -> token(ChatRedisKeys.dictSnapshot())));
    }

    /**
     * 키 하나의 버전. 없거나 머리가 문법에 안 맞으면 빈 {@code Mono} 다.
     *
     * <p>문법은 {@link ProfanityDictionarySnapshotCodec#readVersionToken} 한 곳에 있다 —
     * 발행 스크립트·발행 어댑터와 <b>같은 규칙</b>이어야 한다. 한때 이쪽만
     * {@code Long.parseLong(strip())} 이라 {@code " 12\nbody"} 를 12 로 읽었는데 스크립트는
     * 0 으로 읽었다.
     *
     * <p>깨진 머리에 <b>던지지 않는다.</b> 호출자가 예외를 전송 실패로 분류해 버전을 올리지
     * 않고 재시도하는데 이 상태는 재시도로 낫지 않는다 — 빈 값으로 주면 "아직 없음" 분기가
     * 로그를 남긴다.
     */
    private Mono<Long> token(String key) {
        return redisTemplate.opsForValue().get(key)
                .flatMap(raw -> ProfanityDictionarySnapshotCodec.readVersionToken(raw)
                        .stream().boxed().findFirst()
                        .map(Mono::just)
                        .orElseGet(Mono::empty));
    }
}
