package com.sapari.chat.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.chat.domain.rule.ProfanityFilter;

/**
 * 사전 교체가 <b>실제로 다음 메시지에 닿는지</b>, 그리고 교체 중 독자가 멀쩡한 필터를 보는지 잰다.
 *
 * <p>이 셀이 생긴 이유가 그 첫째다. {@code SendChatService} 가 필터를 생성자로 직접 받던 동안에는
 * 빈을 갈아 끼워도 그 서비스에 안 닿았다 — 필드가 {@code final} 이고 서비스는 부팅 때 한 번
 * 조립된다. 그래서 사전을 바꾸는 유일한 길이 배포였다.
 */
@DisplayName("SwappableProfanityFilter — 교체가 다음 호출에 닿고, 독자는 섞인 상태를 안 본다")
class SwappableProfanityFilterTest {

    private static ProfanityFilter filterOf(String word) {
        return new ProfanityFilter(Set.of(word), Set.of());
    }

    @Test
    @DisplayName("⭐ 교체하면 다음 호출부터 새 사전이 쓰인다 — 이 셀이 없으면 배포만이 길이었다")
    void aSwapReachesTheNextCall() {
        // given
        SwappableProfanityFilter cell = new SwappableProfanityFilter(filterOf("가가가"));
        assertThat(cell.current().filter("나나나 삽니다")).isEqualTo("나나나 삽니다");

        // when: 운영자가 '나나나' 를 사전에 넣었다
        cell.install(filterOf("나나나"));

        // then
        assertThat(cell.current().filter("나나나 삽니다")).isEqualTo("*** 삽니다");
        assertThat(cell.current().filter("가가가 삽니다"))
                .as("교체는 더하기가 아니라 바꿔 끼우기다 — 새 사전에 없는 말은 안 걸린다")
                .isEqualTo("가가가 삽니다");
    }

    @Test
    @DisplayName("null 로 시작하거나 null 로 교체할 수 없다 — 마스킹이 조용히 꺼지는 상태다")
    void nullIsRefusedAtBothEnds() {
        assertThatThrownBy(() -> new SwappableProfanityFilter(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SwappableProfanityFilter(filterOf("가가가")).install(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * ⭐ 교체 중에 읽는 쪽이 <b>언제나 멀쩡한 필터</b>를 집어 든다.
     *
     * <p>필터가 불변이라 성립하는 성질이다 — 교체는 필터를 고치는 것이 아니라 새로 만들어 참조를
     * 바꾸는 것이므로, 한 메시지는 이전 사전이나 다음 사전 중 <b>하나</b>를 쓰고 두 사전이 섞인
     * 상태를 보지 않는다. 그 성질이 깨지는 구현(필터 내부를 고치는 쪽)이면 여기서 트라이가 반쯤
     * 바뀐 상태를 읽어 결과가 둘 중 어느 것도 아니게 된다.
     *
     * <p>재는 방식: 읽는 스레드 여섯이 쉬지 않고 두 문장을 마스킹하고, 다른 스레드가 그 사이
     * 사전을 수백 번 갈아 끼운다. 각 결과는 <b>두 사전 중 하나의 답</b>이어야 한다.
     */
    @Test
    @DisplayName("⭐ 교체 중에도 결과가 두 사전 중 하나다 — 섞인 상태를 보지 않는다")
    void readersNeverSeeAHalfSwappedFilter() throws Exception {
        SwappableProfanityFilter cell = new SwappableProfanityFilter(filterOf("가가가"));
        ProfanityFilter other = filterOf("나나나");
        ProfanityFilter first = cell.current();

        AtomicBoolean stop = new AtomicBoolean();
        AtomicBoolean sawGarbage = new AtomicBoolean();
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(7);
        try {
            List<Future<?>> readers = new java.util.ArrayList<>();
            for (int i = 0; i < 6; i++) {
                readers.add(pool.submit(() -> {
                    go.await(10, TimeUnit.SECONDS);
                    while (!stop.get()) {
                        String out = cell.current().filter("가가가 나나나 삽니다");
                        // 두 사전 중 하나의 답이어야 한다 — 둘 다 걸리거나 둘 다 안 걸리면 섞였다
                        boolean a = out.equals("*** 나나나 삽니다");
                        boolean b = out.equals("가가가 *** 삽니다");
                        if (!a && !b) {
                            sawGarbage.set(true);
                            return null;
                        }
                    }
                    return null;
                }));
            }
            Future<?> swapper = pool.submit(() -> {
                go.await(10, TimeUnit.SECONDS);
                for (int i = 0; i < 2_000; i++) {
                    cell.install(i % 2 == 0 ? other : first);
                }
                return null;
            });
            go.countDown();
            swapper.get(30, TimeUnit.SECONDS);
            stop.set(true);
            for (Future<?> f : readers) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS))
                    .as("스레드가 남으면 다음 테스트에 섞인다")
                    .isTrue();
        }

        assertThat(sawGarbage)
                .as("두 사전 중 어느 답도 아닌 결과가 나왔다 — 교체가 원자가 아니다")
                .isFalse();
    }
}
