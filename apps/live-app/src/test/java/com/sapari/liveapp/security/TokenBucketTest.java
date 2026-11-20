package com.sapari.liveapp.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 토큰 버킷 자체의 계약.
 *
 * <p>필터 테스트는 "넘기면 끊긴다"만 보므로 <b>설정한 속도로 차는지</b>는 확인되지 않는다. 실제로
 * 나노초 단위로 보충량을 미리 계산했을 때 초당 1,000,000 개 미만이 전부 같은 속도로 동작하는 버그가
 * 있었고, 필터 테스트는 전부 통과했다. 여기서 그 회귀를 고정한다.
 */
class TokenBucketTest {

    /** 시간 의존 테스트라 여유를 둔다 — 잡으려는 건 "2배 빠름"이 아니라 "50배 빠름" 급의 회귀다. */
    private static final double TOLERANCE = 3.0;

    @Test
    @DisplayName("설정한 초당 허용량대로 찬다 — 낮은 값이 조용히 무시되던 회귀 방지")
    void refillsAtConfiguredRate() throws InterruptedException {
        int perSecond = 20;
        TokenBucket bucket = new TokenBucket(perSecond, perSecond);

        // 버킷을 비운다
        for (int i = 0; i < perSecond; i++) {
            assertThat(bucket.tryAcquire()).isTrue();
        }
        assertThat(bucket.tryAcquire()).isFalse();

        Thread.sleep(100); // 20/s 면 2개쯤 차야 한다

        int refilled = 0;
        while (bucket.tryAcquire()) {
            refilled++;
        }

        // 상한만 보면 "아예 안 참"(보충량 0)도 통과한다 — 그건 한 번 한도를 넘긴 뒤 영구 차단이라
        // 정상 webhook 이 전량 막히고 방이 Ready 에 갇히는, 가장 비싼 회귀다. 하한을 반드시 함께 본다.
        assertThat(refilled)
                .describedAs("100ms 후 보충된 토큰 (기대 ~2개)")
                .isGreaterThanOrEqualTo(1)
                .isLessThanOrEqualTo((int) (perSecond * 0.1 * TOLERANCE) + 1);
    }

    @Test
    @DisplayName("용량을 넘겨 쌓이지 않는다 — 오래 쉬었다고 버스트가 무한정 커지면 안 된다")
    void doesNotAccumulateBeyondCapacity() throws InterruptedException {
        int capacity = 2;
        TokenBucket bucket = new TokenBucket(capacity, 100);

        Thread.sleep(300); // 100/s 면 상한이 없을 때 30개가 찰 시간

        // 어서션을 사이에 끼우지 않고 한 번에 비운다 — 어서션 지연(첫 호출 클래스로딩 등) 동안
        // 토큰이 다시 차면 무엇을 재는 테스트인지 알 수 없게 된다.
        int drained = 0;
        while (bucket.tryAcquire()) {
            drained++;
        }

        assertThat(drained).describedAs("유휴 300ms 뒤 꺼낼 수 있는 토큰").isEqualTo(capacity);
    }

    /**
     * ⭐ <b>동시 소비가 용량을 넘기지 않는다 — 상한 자체를 재는 유일한 칸.</b>
     *
     * <p>⚠️ 다른 칸들은 전부 단일 스레드다. 그래서 {@code tryAcquire} 의 CAS 소비를
     * {@code set(current - SCALE); return true;} 로 되돌리는 회귀가 그 칸들을 <b>통째로
     * 통과한다</b>(단일 스레드 대조에서 원본·변이 구분 불가). 그 회귀는 "상한" 자체를
     * 거짓으로 만든다.
     *
     * <p>⚠️ <b>한 라운드로는 그 회귀가 33% 빠져나간다.</b> 첫 판이 한 라운드였고, 변이를
     * <b>한 번</b> 돌려 잡힌 것을 보고 "닫혔다" 고 적었다 — 15회 반복에서는 5회가 생존한다.
     * 생존은 상계가 느슨해서가 아니라 <b>경합이 안 일어나서</b>다: 경과가 8~20ms 라 스레드들이
     * 경합 보장 전에 끝난다(생존 회차의 허용 건수가 용량과 같았다). 그래서 라운드를 쌓는다 —
     * 라운드마다 새 버킷을 만들고 각 라운드의 상계를 따로 단정하면 적발이 전수가 된다.
     *
     * <p>단정은 <b>창 기반</b>이다 — 허용 건수가 {@code capacity + refill × 경과} 를 넘지
     * 않아야 한다. 절대값을 못 박지 않는 이유는 리필이 실시간으로 돌아 경과가 측정값이기
     * 때문이고, 여유를 한 토큰 두는 이유는 마지막 성공 뒤에도 시계가 흐르기 때문이다.
     */
    @Test
    @DisplayName("⭐ 동시 소비가 용량을 넘기지 않는다")
    void concurrentConsumptionNeverExceedsTheCeiling() throws Exception {
        // 라운드마다 새 버킷. 한 라운드만 보면 경합이 안 일어난 회차가 그대로 통과한다.
        for (int round = 0; round < 20; round++) {
            assertOneRoundStaysUnderTheCeiling();
        }
    }

    private static void assertOneRoundStaysUnderTheCeiling() throws Exception {
        int capacity = 50;
        int refillPerSecond = 50;
        TokenBucket bucket = new TokenBucket(capacity, refillPerSecond);
        int threads = 32;
        java.util.concurrent.atomic.AtomicInteger granted =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done =
                new java.util.concurrent.CountDownLatch(threads);
        long begin = System.nanoTime();

        for (int t = 0; t < threads; t++) {
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 2_000; i++) {
                        if (bucket.tryAcquire()) {
                            granted.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            worker.setDaemon(true);
            worker.start();
        }
        start.countDown();
        assertThat(done.await(30, java.util.concurrent.TimeUnit.SECONDS))
                .as("30초 안에 끝나야 한다 — 아니면 이 칸이 다른 것을 잰다")
                .isTrue();

        double elapsedSeconds = (System.nanoTime() - begin) / 1_000_000_000.0;
        double ceiling = capacity + refillPerSecond * elapsedSeconds + 1;
        assertThat(granted.get())
                .as("동시 소비가 용량+리필을 넘으면 상한이 거짓이다 — 경과 %.3fs, 상계 %.1f",
                        elapsedSeconds, ceiling)
                .isLessThanOrEqualTo((int) Math.ceil(ceiling));
    }
}
