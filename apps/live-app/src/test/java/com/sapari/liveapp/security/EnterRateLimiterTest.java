package com.sapari.liveapp.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.live.application.port.EnterPopulation;
import com.sapari.live.application.port.LiveMetrics;

/**
 * 상한기 자체의 계약 — 필터를 거치지 않는 자리들.
 *
 * <p>{@link EnterRateLimitFilterTest} 는 HTTP 축(상태 코드 · 헤더 · 체인)을 재고, 여기는
 * 필터가 만들어 낼 수 없는 입력에 대한 계약을 잰다.
 */
class EnterRateLimiterTest {

    /**
     * ⭐ <b>주체 없는 인증 요청도 전역 천장을 받는다 — 전역 버킷의 유일한 독립 벡터다.</b>
     *
     * <p>{@code subject != null} 일 때 전역 버킷의 거부 분기는 <b>도달하지 않는다</b>:
     * 설정 검증이 {@code burst >= permits >= shards} 를 강제하므로 샤드당 용량
     * {@code burst / shards} 는 1 이상이고, 정수 나눗셈 때문에 샤드 용량의 합은 언제나
     * 전역 용량 이하다(같은 식이 리필에도 성립한다). 그래서 샤드가 먼저 막힌다.
     *
     * <p>전역 버킷이 <b>혼자</b> 집행하는 경우는 샤드를 건너뛰는 이 경로 하나다. 그 경로의
     * 분기를 지우면 신원 없는 요청이 무제한으로 서명을 태운다.
     */
    @Test
    @DisplayName("⭐ 주체 없는 인증 요청도 전역 천장을 받는다")
    void nullSubjectStillHitsTheGlobalCeiling() {
        CountingLiveMetrics metrics = new CountingLiveMetrics();
        EnterRateLimiter limiter = new EnterRateLimiter(
                new EnterRateLimitProperties(1, 1, 2, 2, 2), metrics, () -> 0L);

        assertThat(limiter.tryMemberEntry(null)).isTrue();
        assertThat(limiter.tryMemberEntry(null)).isTrue();
        assertThat(limiter.tryMemberEntry(null))
                .as("샤드를 건너뛰어도 전역 용량 2 를 넘지 못한다")
                .isFalse();
        assertThat(metrics.rejections)
                .as("전역 자리의 거부도 집단 태그로 세어져야 한다")
                .containsExactly(EnterPopulation.MEMBER);
    }

    /**
     * ⭐ <b>같은 주체가 인스턴스마다 다른 샤드에 떨어진다 — 샤드를 조준할 수 없다.</b>
     *
     * <p>솔트가 없으면 공격자가 대상의 샤드를 <b>오프라인에서</b> 계산한다: 샤드 키가
     * {@code sub}(=userId)이고 {@code String.hashCode} 는 JDK 규격이며, 미인증으로 열린 목록
     * 응답({@code GetLiveView.LiveRoomSummary.sellerId})이 방송 중 판매자의 userId 를 준다.
     * 그러면 그 샤드에 떨어지는 계정이 나올 때까지 가입만 해서 계정 하나로 특정 판매자를
     * 상시 끊을 수 있다.
     *
     * <p>⚠️ <b>이 칸이 재는 것은 "두 인스턴스의 배치가 갈린다" 는 사실뿐이다.</b> 솔트가
     * 비밀이라는 것도, 해시가 역산 불가라는 것도 재지 않는다 — 후자는 거짓이다
     * ({@code String.hashCode} 는 역산된다). 막는 것은 <b>오프라인 계산</b>이다.
     *
     * <p>표본 1이면 우연히 같은 샤드일 수 있어(64샤드에서 1/64) 주체를 24개 쓴다. 전부
     * 일치할 확률은 64^-24 다.
     */
    @Test
    @DisplayName("⭐ 같은 주체가 인스턴스마다 다른 샤드에 떨어진다 — 조준 불가")
    void theShardOfASubjectDiffersBetweenInstances() {
        EnterRateLimiter first = limiter(64, 1_000, 1_000);
        EnterRateLimiter second = limiter(64, 1_000, 1_000);

        int subjects = 24;
        int agreements = 0;
        for (int i = 0; i < subjects; i++) {
            String subject = "11111111-0000-0000-0000-%012d".formatted(i);
            if (first.shardIndexFor(subject) == second.shardIndexFor(subject)) {
                agreements++;
            }
        }

        assertThat(agreements)
                .as("주체 %d개 전부에서 배치가 같다면 솔트가 섞이지 않은 것이다".formatted(subjects))
                .isLessThan(subjects);
    }

    /**
     * ⭐ <b>같은 인스턴스 안에서는 배치가 안정적이다 — 아니면 계정당 상한이 사라진다.</b>
     *
     * <p>솔트를 호출마다 새로 만들면 매 호출이 다른 샤드로 가고, 한 계정의 지속분이 샤드
     * 리필이 아니라 <b>샤드 수 × 리필</b>이 된다. 솔트가 필드여야 하는 축이 이것이다.
     */
    @Test
    @DisplayName("⭐ 같은 인스턴스에서 같은 주체는 항상 같은 샤드다")
    void theShardOfASubjectIsStableWithinAnInstance() {
        EnterRateLimiter limiter = limiter(64, 1_000, 1_000);
        String subject = "22222222-0000-0000-0000-000000000001";

        int first = limiter.shardIndexFor(subject);
        for (int i = 0; i < 100; i++) {
            assertThat(limiter.shardIndexFor(subject))
                    .as("호출마다 배치가 바뀌면 계정당 몫이 집행되지 않는다")
                    .isEqualTo(first);
        }
    }

    /**
     * ⭐ <b>집계 천장은 전역 버킷이 집행한다 — 샤드 몫의 합이 전역을 넘도록 올려 두었다.</b>
     *
     * <p>샤드 몫을 바닥 나눗셈으로 내면 합이 전역보다 작아 <b>설정에 적은 수가 집행되지
     * 않는다</b>: 이 설정(전역 burst 1,000 · 64샤드)에서 바닥이면 샤드 15 × 64 = <b>960</b>
     * 이 천장이 되고, 운영자는 1,000 을 적어 놓고 960 을 받는다. 올림 + 버스트 헤드룸이면
     * 합이 2,048 이라 전역이 구속한다.
     */
    @Test
    @DisplayName("⭐ 집계 천장이 전역 버킷에서 집행된다 — 샤드 합이 전역을 넘는다")
    void theGlobalBucketEnforcesTheAggregateCeiling() {
        EnterRateLimiter limiter = limiter(64, 1_000, 1_000);

        int admitted = 0;
        for (int i = 0; i < 2_000; i++) {
            // 주체를 흩뿌려 샤드가 구속하지 않게 한다.
            if (limiter.tryMemberEntry("33333333-0000-0000-0000-%012d".formatted(i))) {
                admitted++;
            }
        }

        assertThat(admitted)
                .as("바닥 나눗셈이면 여기가 960 이 된다 — 설정값이 집행되지 않는다는 뜻")
                .isEqualTo(1_000);
    }

    /**
     * ⭐ <b>한 계정이 세게 때려도 전역 버킷을 비우지 못한다 — 샤드 검사가 먼저다.</b>
     *
     * <p>전역을 먼저 두면 <b>샤드에서 떨어질 요청까지 전역 토큰을 소모</b>하므로 계정 하나가
     * 자기 몫을 넘어 전역을 비운다. 샤드를 먼저 두면 그 요청은 전역을 건드리지 않는다.
     *
     * <p>호출 수를 샤드 몫(⌈100/4⌉×2 = 50)보다 훨씬 크게 둔 것이 그 축이다 — 몫만큼만 보내면
     * 두 순서가 같은 결과를 내서 아무것도 가리지 못한다.
     */
    @Test
    @DisplayName("⭐ 한 계정이 전역 버킷을 비우지 못한다 — 샤드가 먼저 막는다")
    void oneAccountCannotDrainTheGlobalBucket() {
        EnterRateLimiter limiter = limiter(4, 100, 100);
        String attacker = "44444444-0000-0000-0000-000000000001";

        for (int i = 0; i < 400; i++) {
            limiter.tryMemberEntry(attacker);
        }

        int left = 0;
        for (int i = 0; i < 200; i++) {
            if (limiter.tryMemberEntry("55555555-0000-0000-0000-%012d".formatted(i))) {
                left++;
            }
        }

        assertThat(left)
                .as("전역 100 중 공격자가 쓸 수 있는 것은 자기 샤드 몫 50 뿐이다 — "
                        + "전역을 먼저 검사하면 400 을 다 태워 여기가 0 이 된다")
                .isGreaterThanOrEqualTo(40);
    }

    private static EnterRateLimiter limiter(int shards, int burst, int permits) {
        return new EnterRateLimiter(
                new EnterRateLimitProperties(permits, burst, permits, burst, shards),
                LiveMetrics.NOOP, () -> 0L);
    }

    /**
     * ⭐ <b>지속 천장도 전역 버킷이 집행한다 — 샤드 <i>리필</i>의 합이 전역을 넘는다.</b>
     *
     * <p>용량(버스트)과 리필은 <b>다른 축</b>이다. 버스트만 올림으로 내고 리필을 바닥으로
     * 두면 1초 뒤 돌아오는 양이 샤드 합 {@code 64 × ⌊1000/64⌋ = 960} 으로 묶여, 설정에 적은
     * 지속 1,000/s 가 집행되지 않는다. 그 축은 시계를 움직여야 보이므로 버스트 칸으로는
     * 분모가 0 이다 — 실제로 역변이에서 그 칸만 놓쳤다.
     */
    @Test
    @DisplayName("⭐ 1초 뒤 돌아오는 양도 전역이 집행한다 — 샤드 리필 합이 전역을 넘는다")
    void theGlobalBucketEnforcesTheSustainedCeiling() {
        java.util.concurrent.atomic.AtomicLong nanos = new java.util.concurrent.atomic.AtomicLong();
        EnterRateLimiter limiter = new EnterRateLimiter(
                new EnterRateLimitProperties(1_000, 1_000, 1_000, 1_000, 64),
                LiveMetrics.NOOP, nanos::get);

        // 전역·샤드를 <전부> 비운다. 4,000 으로는 샤드마다 잔여가 남아 1초 뒤 (잔여 + 리필)이
        // 전역 1,000 을 덮어 버렸다 — 그러면 리필 축이 가려진다(실측: 바닥 리필로 되돌려도
        // 통과 수가 1,000 이었다). 샤드당 도착 수를 용량보다 충분히 크게 둔다.
        for (int i = 0; i < 40_000; i++) {
            limiter.tryMemberEntry("66666666-0000-0000-0000-%012d".formatted(i));
        }

        nanos.set(1_000_000_000L); // 정확히 1초

        // 샤드를 확실히 비울 만큼 보낸다. 4,000 으로는 샤드마다 남는 토큰이 생겨 통과 수가
        // 전역 리필(1,000)에 못 미쳤다 — 실측 792. 그 수로 두면 이 칸이 재는 축이
        // "전역이 집행한다" 가 아니라 "부하가 모자랐다" 가 된다.
        int admitted = 0;
        for (int i = 0; i < 40_000; i++) {
            if (limiter.tryMemberEntry("77777777-0000-0000-0000-%012d".formatted(i))) {
                admitted++;
            }
        }

        assertThat(admitted)
                .as("샤드 리필을 바닥으로 두면 여기가 960 이 된다 — 설정한 1,000/s 가 "
                        + "집행되지 않는다는 뜻")
                .isEqualTo(1_000);
    }
}
