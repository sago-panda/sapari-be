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
}
