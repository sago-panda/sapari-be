package com.sapari.chat.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import com.sapari.chat.application.protocol.ChatAccountEvent;

import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * 구독 어댑터가 <b>봉투 하나에 죽지 않는지</b>와 <b>모르는 필드를 견디는지</b>를 고정한다.
 *
 * <p>둘 다 되돌려도 아무것도 안 깨지던 자리였다. 앞은 구독이 죽으면 그 Pod가 재시작까지 모든 계정 조치를
 * 놓치는 것이고, 뒤는 봉투에 필드가 하나 늘 때 롤링 배포 중 구버전 Pod가 <b>전부</b> 이벤트를 버리는
 * 것이다 — 둘 다 조용하고, 둘 다 밴 집행이 함대 단위로 사라진다.
 *
 * <p>생성자가 구독을 열므로 템플릿을 목으로 둔다. 여기서 재는 것은 Redis의 동작이 아니라 <b>문자열
 * 하나를 어떻게 다루는가</b>이고, 그건 {@code parse}만 붙잡으면 그대로 확인된다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RedisChatAccountEventSource — 깨진 봉투에도, 늘어난 필드에도 살아남는다")
class RedisChatAccountEventSourceTest {

    @Mock
    private ReactiveStringRedisTemplate redis;

    private RedisChatAccountEventSource source() {
        // 생성자가 listenToChannel을 부른다 — 구독 자체는 여기서 재지 않으므로 빈 스트림이면 충분하다.
        org.mockito.BDDMockito.given(redis.listenToChannel(org.mockito.ArgumentMatchers.anyString()))
                .willReturn(Flux.empty());
        return new RedisChatAccountEventSource(redis);
    }

    @Test
    @DisplayName("정상 봉투는 그대로 복원된다")
    void parsesAWellFormedEnvelope() {
        // given
        UUID userId = UUID.randomUUID();

        // when & then
        StepVerifier.create(source().parse("{\"kind\":\"BANNED\",\"userId\":\"" + userId + "\"}"))
                .expectNext(new ChatAccountEvent.Banned(userId))
                .expectComplete()
                .verify(java.time.Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("⭐ 모르는 필드가 있어도 읽는다 — 엄격하면 봉투에 필드가 느는 순간 구버전 Pod가 전부 버린다")
    void toleratesUnknownFields() {
        // given: 새 버전이 필드를 하나 더한 봉투. 롤링 배포 중 구버전 Pod가 받는 모양이다.
        UUID userId = UUID.randomUUID();
        String withNewField = "{\"kind\":\"BANNED\",\"userId\":\"" + userId + "\",\"expiresAt\":\"2026-10-06T00:00:00Z\"}";

        // when & then: 버리면 그 Pod의 밴 집행이 조용히 C2 이전으로 돌아간다
        StepVerifier.create(source().parse(withNewField))
                .expectNext(new ChatAccountEvent.Banned(userId))
                .expectComplete()
                .verify(java.time.Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("⭐ 깨진 봉투는 건너뛰고 스트림은 산다 — 죽으면 이 Pod가 재시작까지 모든 조치를 놓친다")
    void skipsABrokenEnvelopeWithoutFailing() {
        // when & then: 에러가 아니라 빈 완료여야 상위 flatMap이 다음 이벤트를 계속 받는다
        StepVerifier.create(source().parse("이건 JSON이 아니다"))
                .expectComplete()
                .verify(java.time.Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("모르는 종류도 스트림을 죽이지 않는다 — 새 kind를 먼저 배포하는 순서에서 나온다")
    void skipsAnUnknownKind() {
        // when & then
        StepVerifier.create(source().parse("{\"kind\":\"WITHDRAWN\",\"userId\":\"" + UUID.randomUUID() + "\"}"))
                .expectComplete()
                .verify(java.time.Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("null payload도 건너뛴다")
    void skipsNullPayload() {
        // when & then
        StepVerifier.create(source().parse(null))
                .expectComplete()
                .verify(java.time.Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("채널 이름이 계정 채널이다 — 어긋나면 아무도 받지 못한다")
    void subscribesToTheAccountChannel() {
        // when
        source();

        // then
        org.mockito.BDDMockito.then(redis).should().listenToChannel("chat:account:events");
    }
}
