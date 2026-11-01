package com.sapari.chat.infrastructure.redis;

import java.util.UUID;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Repository;

import com.sapari.chat.domain.repository.ChatBanRepository;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * {@code chat:banned:{userId}} 어댑터 — 키 존재 여부가 곧 밴이다.
 *
 * <p><b>값을 읽지 않는다.</b> 만료가 곧 해제라 상태를 담을 값이 필요 없고, 그래서 조회가 {@code EXISTS}
 * 하나로 끝난다. 기간 밴은 TTL이 지나면 키가 사라지고 영구 밴은 TTL이 없다.
 *
 * <p><b>이 키에는 타입 충돌 위험이 없다</b> — 읽기가 {@code EXISTS}라 타입을 보지 않기 때문이다. 강퇴 명단({@code SISMEMBER})이 WRONGTYPE에 노출돼 별도 예외로
 * 갈라야 했던 것과 다르므로, 그 분류를 여기까지 넓히지 않는다. 종료 마커가 같은 이유로 그냥 두는 것과 같다.
 *
 * <p>실패는 흡수하지 않고 전파한다. 통과시킬지 막을지는 입장 게이트의 정책이고, 여기서 {@code false}로
 * 삼키면 "확실히 정상"과 "조회 불가"가 같은 값이 되어 그 판단을 되돌릴 수 없다.
 */
@Repository
@RequiredArgsConstructor
public class ChatBanRedisRepository implements ChatBanRepository {

    private final ReactiveStringRedisTemplate redisTemplate;

    @Override
    public Mono<Boolean> isBanned(UUID userId) {
        return redisTemplate.hasKey(ChatRedisKeys.banned(userId));
    }
}
