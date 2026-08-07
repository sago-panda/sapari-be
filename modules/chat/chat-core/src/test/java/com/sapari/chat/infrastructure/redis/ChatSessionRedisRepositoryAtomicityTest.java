package com.sapari.chat.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * 등재와 TTL이 갈라지지 않는지만 보는 테스트 — 실제 Redis가 필요 없어 컨테이너 테스트와 분리했다.
 * (같은 클래스에 두면 Docker 없는 환경에서 이 검증까지 못 돈다.)
 */
class ChatSessionRedisRepositoryAtomicityTest {

    private final UUID roomId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @Test
    @DisplayName("add — 등재와 TTL이 한 번의 호출로 나간다(TTL 없는 키가 남을 틈이 없다)")
    @SuppressWarnings("unchecked")
    void add_sets_ttl_in_the_same_call() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), anyList())).thenReturn(Flux.just(1L));

        StepVerifier.create(new ChatSessionRedisRepository(redis).add(roomId, "s1", userId))
                .verifyComplete();

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<Object>> args = ArgumentCaptor.forClass(List.class);
        verify(redis).execute(any(RedisScript.class), keys.capture(), args.capture());

        assertThat(keys.getValue()).containsExactly("room:" + roomId + ":sessions");
        // TTL이 인자에 실려 같은 호출로 나가는지 — 두 번의 왕복으로 나뉘면 그 사이가 빈다
        assertThat(args.getValue()).containsExactly("s1", userId.toString(), "86400");
    }

    @Test
    @DisplayName("add — Redis 실패는 삼키지 않고 전파한다(입장 허용 판단은 호출자 몫)")
    @SuppressWarnings("unchecked")
    void add_propagates_failure() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), anyList()))
                .thenReturn(Flux.error(new RuntimeException("redis blip")));

        StepVerifier.create(new ChatSessionRedisRepository(redis).add(roomId, "s1", userId))
                .verifyErrorMessage("redis blip");
    }
}
