package com.sapari.chat.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

import java.time.Duration;
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

    /** 이 테스트들은 낡음 판정을 재지 않는다 — 실제 시계로 충분하다(그 판정은
     * ChatSessionHeartbeatTest 가 합성 시계로 잰다). */
    private static final com.sapari.global.time.TimeProvider SYSTEM_TIME =
            new com.sapari.global.time.TimeProvider(java.time.Clock.systemUTC());


    /** 반응형 종결자(.block() / StepVerifier) 상한 — 없으면 회귀가 테스트를 실패시키는 대신 <b>멈추게</b> 한다(근거: modules/chat/AGENTS.md). */
    private static final Duration VERIFY_CEILING = Duration.ofSeconds(10);
    private final UUID roomId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @Test
    @DisplayName("add — 등재와 TTL이 한 번의 호출로 나간다(TTL 없는 키가 남을 틈이 없다)")
    @SuppressWarnings("unchecked")
    void add_sets_ttl_in_the_same_call() {
        // given
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        given(redis.execute(any(RedisScript.class), anyList(), anyList())).willReturn(Flux.just(1L));

        // when
        StepVerifier.create(new ChatSessionRedisRepository(redis, SYSTEM_TIME).add(roomId, "s1", userId))
                .expectComplete().verify(VERIFY_CEILING);

        // then
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<Object>> args = ArgumentCaptor.forClass(List.class);
        then(redis).should(times(1)).execute(any(RedisScript.class), keys.capture(), args.capture());

        assertThat(keys.getValue()).containsExactly("chat:room:" + roomId + ":sessions");
        // TTL이 인자에 실려 같은 호출로 나가는지 — 두 번의 왕복으로 나뉘면 그 사이가 빈다.
        // 값은 userId:마지막확인시각 이다 — 좀비 항목을 집계에서 빼려면 그 시각이 값에 있어야 한다
        // (ChatSessionHeartbeatTest 가 그 판정을 잰다). 여기서는 형식만 본다.
        assertThat(args.getValue()).hasSize(3);
        assertThat(String.valueOf(args.getValue().get(0))).isEqualTo("s1");
        assertThat(String.valueOf(args.getValue().get(1))).matches(userId + ":\\d+");
        assertThat(String.valueOf(args.getValue().get(2))).isEqualTo("86400");
    }

    @Test
    @DisplayName("add — Redis 실패는 삼키지 않고 전파한다(입장 허용 판단은 호출자 몫)")
    @SuppressWarnings("unchecked")
    void add_propagates_failure() {
        // given
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        given(redis.execute(any(RedisScript.class), anyList(), anyList()))
                .willReturn(Flux.error(new RuntimeException("redis blip")));

        // when & then
        StepVerifier.create(new ChatSessionRedisRepository(redis, SYSTEM_TIME).add(roomId, "s1", userId))
                .expectErrorMessage("redis blip").verify(VERIFY_CEILING);
    }
}
