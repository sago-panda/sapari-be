package com.sapari.streamingapp.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import com.sapari.global.logging.LogFields;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.Context;

/**
 * 리액티브 코드의 로그에 상관 식별자가 붙는지 본다.
 *
 * <p>왜 이 테스트가 필요한가: MDC 는 {@code ThreadLocal} 이고 Reactor 는 신호마다 스레드를 바꾼다.
 * 실측으로 {@code publishOn} 뒤와 <b>{@code onErrorResume} 안에서 값이 {@code null}</b> 이었는데,
 * 이 저장소의 열화 로그(fail-open·강등·전달 포기)가 거의 전부 그 람다 안이다 — 가장 알고 싶은 줄이
 * 가장 확실하게 식별자를 잃는 구조였다.
 *
 * <p>그래서 단언을 "같은 스레드에서 보인다" 로 두지 않는다. 그건 아무것도 하지 않아도 통과한다.
 */
@DisplayName("리액티브 상관 식별자 — 스레드를 넘고 에러 분기 안에서도 남는다")
class ReactiveLogContextTest {

    /** 반응형 종결자(.block() / StepVerifier) 상한 — 없으면 회귀가 테스트를 실패시키는 대신 <b>멈추게</b> 한다(근거: modules/chat/AGENTS.md). */
    private static final Duration VERIFY_CEILING = Duration.ofSeconds(10);

    @BeforeAll
    static void wire() {
        // 운영과 같은 배선을 쓴다 — 테스트가 자기 방식으로 등록하면 운영 경로를 재지 않는다.
        new ReactiveLogContextConfig().reactiveLogContext();
    }

    @Test
    @DisplayName("⭐ 스레드가 바뀌어도 남는다 — MDC 단독으로는 여기서 null 이었다")
    void theContextSurvivesAThreadHop() {
        // given
        AtomicReference<String> seen = new AtomicReference<>();

        // when
        Mono.just("x")
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(value -> seen.set(MDC.get(LogFields.SESSION_ID)))
                .contextWrite(Context.of(LogFields.SESSION_ID, "s-42"))
                .block(VERIFY_CEILING);

        // then
        assertThat(seen.get()).isEqualTo("s-42");
    }

    @Test
    @DisplayName("⭐ onErrorResume 안에서도 남는다 — 이 저장소의 열화 로그가 사는 자리다")
    void theContextSurvivesIntoTheFailOpenBranch() {
        // given
        AtomicReference<String> seenSession = new AtomicReference<>();
        AtomicReference<String> seenRoom = new AtomicReference<>();

        // when: 실제 fail-open 모양 — 상류 에러를 흡수하고 통과시킨다
        String result = Mono.just("x")
                .publishOn(Schedulers.boundedElastic())
                .flatMap(value -> Mono.<String>error(new IllegalStateException("Redis down")))
                .onErrorResume(error -> {
                    seenSession.set(MDC.get(LogFields.SESSION_ID));
                    seenRoom.set(MDC.get(LogFields.ROOM_ID));
                    return Mono.just("fail-open");
                })
                .contextWrite(Context.of(LogFields.SESSION_ID, "s-42", LogFields.ROOM_ID, "ROOM-9"))
                .block(VERIFY_CEILING);

        // then
        assertThat(result).isEqualTo("fail-open");
        assertThat(seenSession.get()).as("fail-open 분기에서 session_id 를 잃었다").isEqualTo("s-42");
        assertThat(seenRoom.get()).as("fail-open 분기에서 room_id 를 잃었다").isEqualTo("ROOM-9");
    }

    @Test
    @DisplayName("⭐ 컨텍스트 밖에서는 남의 값이 붙지 않는다 — 비는 것보다 틀린 식별자가 나쁘다")
    void aLaterSubscriptionDoesNotInheritTheValue() {
        // given: 먼저 값을 실은 체인을 돌린다
        Mono.just("x")
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(value -> MDC.get(LogFields.SESSION_ID))
                .contextWrite(Context.of(LogFields.SESSION_ID, "s-42"))
                .block(VERIFY_CEILING);

        AtomicReference<String> seen = new AtomicReference<>("(미실행)");

        // when: 컨텍스트 없이 같은 스케줄러를 쓴다 — 스레드가 재사용될 수 있다
        Mono.just("y")
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(value -> seen.set(String.valueOf(MDC.get(LogFields.SESSION_ID))))
                .block(VERIFY_CEILING);

        // then: 앞 요청의 식별자가 새어 나오면 조사가 엉뚱한 세션으로 간다
        assertThat(seen.get()).as("스레드 재사용으로 앞 요청의 식별자가 샜다").isEqualTo("null");
    }
}
