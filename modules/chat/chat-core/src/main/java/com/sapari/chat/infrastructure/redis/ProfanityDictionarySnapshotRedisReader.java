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
     *
     * <p>⚠️ <b>발행 쪽과 같은 규칙으로 두 키의 큰 쪽을 집는다 — "버전 키 우선" 이 아니다.</b>
     *
     * <p>한때 "버전 키가 읽히면 그것, 비면 본문 머리로 폴백" 이었다. 발행 쪽은 같은 두 키를
     * {@code max} 로 집고 그 근거를 "어느 한 키의 유실이 번호를 내리지 못한다" 로 적는데, 같은
     * 근거가 받는 쪽에 적용되지 않는 <b>비대칭</b>이었다. 버전 키가 <b>읽히면서 본문 머리보다
     * 낮은</b> 상태에서 두 쪽 판정이 갈리고, 그러면 전 Pod 이 분마다 스냅샷을 다시 읽고 아무것도
     * 교체하지 않으며 로그도 0줄이 된다.
     *
     * <p>인밴드 도달 경로는 못 찾았다 — 두 키를 한 스크립트가 같은 수로 쓴다. 그래도 맞추는
     * 이유는 그 불변식이 <b>스크립트 밖 쓰기</b>에 대해 보장되지 않기 때문이다. (한때 이 자리가
     * 그 근거로 "이 클래스에 {@code TYPE} 가드가 있는 이유가 바로 그것이다" 를 들었는데
     * <b>거짓이다</b> — 바로 위 클래스 javadoc 이 "WRONGTYPE 을 따로 다루지 않는다" 를 적고
     * {@code RedisWrongType} 전수에서 이 파일은 0건이다. 그 가드는 발행자의 {@code tokenOf} 와
     * Lua 의 {@code TYPE} 호출에 있다.)
     *
     * <p>⚠️ <b>그래서 두 번째 키를 머리만 읽는다.</b> 처음에 {@code GET} 으로 바꿨는데 그 키가
     * 나르는 것은 <b>스냅샷 본문 전체</b>다 — 번들 기준 11,345 B 에 ADMIN 델타가 더 붙는다.
     * 그런데 {@code ProfanityDictionaryRefreshHandler} 가 버전 키를 따로 둔 이유를 "비용은 Pod
     * 당 이 간격마다 {@code GET} 하나(몇 바이트)다 — 스냅샷 전체(수십 KB)가 아닌 것이 버전 키를
     * 따로 둔 이유다" 로 적는다. 무조건 경로에서 그 이유를 지우는 것이라
     * {@code GETRANGE 0..MAX} 로 머리만 당긴다 — 같은 {@code max} 성질을 몇 바이트로 얻는다.
     *
     * <p>빈 {@code Mono} 는 <b>두 키가 다 읽히지 않을 때</b> 나온다. 그것을 호출자가 "아직
     * 발행되지 않았다" 로 쓰는데, 엄밀히는 <b>"한 번도 없었다" 와 "있었는데 사라졌다" 를 구분한
     * 것이 아니다</b> — 휘발 저장만으로는 그 둘이 구별되지 않는다({@code
     * ProfanityDictionarySnapshotRedisPublisher} javadoc 의 환원 불가 축). 빈 값에서 번들로 뜨는
     * 것이 두 경우 모두에 안전한 선택이라 가르지 않는다.
     */
    @Override
    public Mono<Long> readVersion() {
        return token(ChatRedisKeys.dictVersion())
                .concatWith(Mono.defer(() -> token(ChatRedisKeys.dictSnapshot())))
                .reduce(Math::max);
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
        return read(key)
                .flatMap(raw -> ProfanityDictionarySnapshotCodec.readVersionToken(raw)
                        .stream().boxed().findFirst()
                        .map(Mono::just)
                        .orElseGet(Mono::empty))
                // ⚠️ 타입이 오염된 키는 <기여하지 않는다>(빈 값). 발행 쪽이 같은 상황에서 0 으로
                // 떨어뜨려 치유를 살리는데 여기만 에러로 올리면, 스냅샷 키 하나가 오염된 동안
                // readVersion() 이 매번 실패해 RefreshHandler 의 "읽지 못했다" WARN 이 Pod 당
                // 분당 한 줄씩 나간다 — max 로 맞추면서 이 반쪽을 안 가져왔었다.
                .onErrorResume(RedisWrongType::matches, e -> Mono.empty());
    }

    /**
     * 키를 읽는다 — 스냅샷은 <b>머리만</b> 당긴다.
     *
     * <p>{@code GETRANGE 0..MAX} 는 최대 {@code MAX+1} 바이트다. 머리가 상한 자리수면 그 뒤
     * 개행까지 들어오고, 상한보다 길면 개행 없이 숫자만 들어와 문법이 거부한다 — 어느 쪽이든
     * 전체를 당기지 않고 같은 판정이 나온다. 없는 키는 빈 문자열이라 빈 {@code Mono} 로 접는다.
     */
    private Mono<String> read(String key) {
        if (!key.equals(ChatRedisKeys.dictSnapshot())) {
            return redisTemplate.opsForValue().get(key);
        }
        return redisTemplate.opsForValue()
                .get(key, 0, ProfanityDictionarySnapshotCodec.MAX_VERSION_DIGITS)
                .filter(raw -> !raw.isEmpty());
    }
}
