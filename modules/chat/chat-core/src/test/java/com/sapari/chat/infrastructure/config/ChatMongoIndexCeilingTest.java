package com.sapari.chat.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.index.ReactiveIndexOperations;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;

import com.sapari.chat.infrastructure.persistence.document.ChatMessageDocument;

import reactor.core.publisher.Mono;

/**
 * 부팅 인덱스 생성에 <b>상한이 실제로 걸리고</b>, 넘겼을 때 사유가 번역되는지 본다.
 *
 * <p>왜 따로 두는가: 상한을 {@code .block()} 으로 되돌려도 다른 테스트는 전부 초록이다 — 컨테이너
 * 테스트의 Mongo 는 늘 응답하므로 상한에 도달할 일이 없다. 그래서 응답하지 않는 Mongo 를 만들어
 * 그 경로를 직접 밟는다.
 *
 * <p>번역을 함께 재는 이유는 조치가 갈리기 때문이다. 생 {@code IllegalStateException: Timeout on
 * blocking read} 만 보면 당직자가 "Mongo 가 죽었나" 와 "큰 컬렉션에 인덱스를 새로 만드는 중이었나" 를
 * 가를 수 없는데, 전자는 Mongo 를 보고 후자는 기다리거나 상한을 올려야 한다.
 *
 * <p>⚠️ <b>호출을 별 스레드에 태워 바깥에서 기다린다.</b> 처음에는 이 스레드에서 직접 불렀는데,
 * 그러면 상한을 {@code .block()} 으로 되돌렸을 때 테스트가 <b>실패하는 대신 멈춘다</b>
 * ({@code Mono.never()} 를 무한히 기다린다 — 실측: 되돌림 확인이 600초를 넘겨 중단했다). 멈춘 회차는
 * 그 한 건만 숨기는 게 아니라 보고 자체를 못 하므로 같은 실행의 되돌림 판정 전부를 무효로 만든다.
 * 이 저장소가 세운 규칙이 정확히 그것이고({@code modules/chat/AGENTS.md} — "빨간 테스트는 그렇다고
 * 말해야 한다"), 그 규칙을 가드의 테스트가 어기면 가드를 믿을 수 없다.
 */
@DisplayName("부팅 인덱스 생성 — 상한이 걸리고 사유가 번역된다")
class ChatMongoIndexCeilingTest {

    /** 테스트가 10분을 기다릴 수는 없다 — 상한만 바꿔 끼운다. */
    private static final Duration TINY_CEILING = Duration.ofMillis(200);

    /**
     * 인덱스 생성을 별 스레드에서 돌리고 <b>바깥에서</b> 기다린다 — 상한이 사라져도 이 테스트는
     * 멈추지 않고 사유와 함께 실패한다.
     *
     * <p>⚠️ <b>전용 데몬 executor 다 — {@code commonPool} 이 아니다.</b> 처음엔
     * {@code CompletableFuture.supplyAsync} 로 commonPool 에 태우고 타임아웃에 {@code cancel(true)}
     * 를 불렀는데, <b>{@code CompletableFuture.cancel} 은 워커를 인터럽트하지 않는다</b>(실측: 호출
     * 뒤에도 {@code ForkJoinPool.commonPool-worker-1} 이 {@code WAITING}·{@code alive=true}).
     * 되돌림 확인 한 회차마다 commonPool 워커 하나가 {@code Mono.never().block()} 에 영구히 남고,
     * 같은 JVM 의 뒤 테스트가 commonPool 을 쓰면 그 오염을 물려받는다.
     *
     * <p>{@code shutdownNow()} 는 실제로 푼다(실측: Reactor 가
     * {@code Exceptions$ReactiveException: InterruptedException} 을 던지고 executor 가
     * {@code terminated}). 그래서 통과 경로든 실패 경로든 {@code finally} 에서 한 번 부른다.
     */
    private static Throwable failureOf(ReactiveMongoTemplate template, Duration ceiling) {
        ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "index-ceiling-probe");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<List<String>> call = worker.submit(
                    () -> ChatMongoConfig.createChatMessagesIndexes(template, ceiling));
            List<String> created = call.get(5, TimeUnit.SECONDS);
            throw new AssertionError("상한에 걸리지 않고 끝났다 — 생성됨=" + created);
        } catch (TimeoutException e) {
            throw new AssertionError(
                    "상한이 걸리지 않았다 — 5초 안에 끝나지 않았다. block(Duration) 이 아니라 "
                            + "block() 으로 돌아간 것이다(그러면 운영에서 파드가 조용히 멈춘다).", e);
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("기다리는 중 인터럽트됐다", e);
        } finally {
            worker.shutdownNow();
        }
    }

    private static ReactiveMongoTemplate templateThatNeverAnswers() {
        ReactiveIndexOperations ops = mock(ReactiveIndexOperations.class);
        // 응답하지 않는 Mongo — socketReadTimeoutMs=0(무한)이면 드라이버 쪽에 이걸 끊을 것이 없다
        given(ops.createIndex(any())).willReturn(Mono.never());
        ReactiveMongoTemplate template = mock(ReactiveMongoTemplate.class);
        given(template.indexOps(ChatMessageDocument.class)).willReturn(ops);
        return template;
    }

    @Test
    @DisplayName("⭐ 응답하지 않으면 상한에서 죽는다 — 상한이 없으면 파드가 조용히 멈춘다")
    void anUnresponsiveMongoFailsAtTheCeiling() {
        assertThat(failureOf(templateThatNeverAnswers(), TINY_CEILING))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("⭐ 상한은 인덱스 전체에 걸린다 — 건당이면 최악이 건수에 비례한다")
    void theCeilingBoundsTheWholeMethodNotEachCall() {
        // 호출들이 각각 상한 바로 아래로 끝나면 아무것도 던지지 않고 누적된다 — 그게 건당 상한의
        // 함정이다(실측: 상한 400ms × 4건, 각 300ms → 총 1,279ms, 던짐 0). 데드라인을 한 번 잡으면
        // 총 경과가 상한에 묶인다.
        //
        // ⚠️ 건당으로 되돌렸을 때 실제로 잡는 자리는 아래 경과 단언이 아니라 failureOf 의
        // "안 던졌다" 가드다 — 전부 상한 아래라 정상 반환하므로 경과 단언에는 도달하지 않는다.
        // 경과 단언이 막는 것은 다른 변형이다: 던지기는 하는데 데드라인이 공유되지 않아 늦게 던지는 꼴.
        ReactiveIndexOperations ops = mock(ReactiveIndexOperations.class);
        given(ops.createIndex(any()))
                .willReturn(Mono.delay(Duration.ofMillis(300)).thenReturn("slow"));
        ReactiveMongoTemplate template = mock(ReactiveMongoTemplate.class);
        given(template.indexOps(ChatMessageDocument.class)).willReturn(ops);

        long started = System.nanoTime();
        Throwable failure = failureOf(template, Duration.ofMillis(400));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(failure).isInstanceOf(IllegalStateException.class);
        assertThat(elapsed)
                .as("총 경과가 상한을 크게 넘었다 — 상한이 호출마다 걸려 누적된 것이다(건수에 비례)")
                .isLessThan(Duration.ofMillis(1_200));
    }

    @Test
    @DisplayName("⭐ 실패 사유가 두 갈래를 다 적는다 — 조치가 정반대라 가려 줘야 한다")
    void theFailureNamesBothCauses() {
        assertThat(failureOf(templateThatNeverAnswers(), TINY_CEILING))
                .hasMessageContaining("chat_messages 인덱스 생성이")
                // (1) Mongo 가 죽은 쪽 — 왜 이 상한이 유일한 경계인지까지
                .hasMessageContaining("socketReadTimeoutMs=0")
                // (2) 큰 컬렉션에 처음 만드는 쪽 — 환산 근거와 두 가지 조치
                .hasMessageContaining("0.0057ms")
                .hasMessageContaining("INDEX_CREATE_CEILING")
                .hasMessageContaining("CrashLoopBackOff")
                // 어느 인덱스였는지 — 첫 번째에서 걸린다
                .hasMessageContaining("roomId+_id");
    }

    @Test
    @DisplayName("원인 예외를 버리지 않는다 — 스택이 끊기면 진짜 원인을 못 쫓는다")
    void theOriginalCauseIsKept() {
        assertThat(failureOf(templateThatNeverAnswers(), TINY_CEILING))
                .cause().isInstanceOf(IllegalStateException.class);
    }
}
