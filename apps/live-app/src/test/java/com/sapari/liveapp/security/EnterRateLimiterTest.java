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
                new EnterRateLimitProperties(1, 1, 2, 2, 2, null, null, null), metrics, () -> 0L);

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
     * <p>표본 1이면 우연히 같은 샤드일 수 있어(64샤드에서 1/64) 주체를 24개 쓴다.
     *
     * <p>⚠️ <b>이 줄은 한때 "전부 일치할 확률은 64^-24 다" 였고 그때는 거짓이었다 — 실제는
     * 1/64 였다(42자리 차이).</b> 당시 배치가 문자열 솔트를 <b>이어 붙이는</b> 방식이어서
     * 솔트가 샤드 인덱스에 <b>상수 회전</b>으로만 들어갔고({@code 31^36 ≡ 1 (mod 64)}),
     * 그래서 24개 주체의 일치가 서로 독립이 아니라 <b>완전상관</b>이었다 — 회전이 0이면
     * 24개가 모두 일치하고 아니면 하나도 일치하지 않는다. 즉 이 칸은 1/64 로 빨강이었다
     * (실측: 회전 0 비율 1.435%, 그리고 전수 8회 중 1회 관측 — 관측은 근거가 아니고 계산이 근거다).
     * {@code shardKey} 가 MAC 이 된 뒤로 주체별 배치가 독립이므로 <b>이제 64^-24 가 참이다.</b>
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
     * ⭐ <b>배치 차이가 주체마다 달라야 한다 — 상수 회전이면 솔트가 소거된다.</b>
     *
     * <p>위 칸({@link #theShardOfASubjectDiffersBetweenInstances()})은 "적어도 하나는 다르다" 만
     * 본다. 그것만으로는 <b>깨진 성질</b>을 못 잡는다 — 이어 붙이는 솔트는 모든 주체에 <b>같은
     * 회전</b>을 더하므로 "하나는 다르다" 를 (회전 ≠ 0 인 63/64 의 경우) 만족시키면서도 공격자에게
     * 필요한 관계를 그대로 남긴다. 공격자가 원하는 것은 절대 번호가 아니라 "내 계정이 타깃과 같은
     * 샤드인가" 이고, 회전은 양쪽에 똑같이 더해져 그 관계에서 소거된다.
     *
     * <p>그래서 차이 집합을 직접 본다. 실측(SPR-179): 이어 붙이던 때 주체 24개의 차이가
     * <b>20,000/20,000 쌍에서 상수</b>였다.
     */
    @Test
    @DisplayName("⭐ 인스턴스 간 배치 차이가 주체마다 다르다 — 상수 회전이면 충돌 관계가 그대로 남는다")
    void theShardDifferenceIsNotAConstantRotation() {
        EnterRateLimiter first = limiter(64, 1_000, 1_000);
        EnterRateLimiter second = limiter(64, 1_000, 1_000);

        java.util.Set<Integer> deltas = new java.util.HashSet<>();
        for (int i = 0; i < 24; i++) {
            String subject = "33333333-0000-0000-0000-%012d".formatted(i);
            deltas.add(Math.floorMod(first.shardIndexFor(subject) - second.shardIndexFor(subject), 64));
        }

        assertThat(deltas)
                .as("차이가 하나뿐이다 — 솔트가 상수 회전으로만 들어가 충돌 관계에서 소거된다")
                .hasSizeGreaterThan(1);
    }

    /**
     * ⭐ <b>한 인스턴스에서 알아낸 충돌이 다른 인스턴스로 옮겨지지 않는다.</b>
     *
     * <p>이것이 이 방어가 실제로 막아야 하는 것이다. 공격자는 샤드 번호를 모르고도 "내 계정이
     * 타깃과 같은 샤드" 인 계정을 고를 수 있으면 충분하다 — 가입 스로틀이 없으므로 그 계정이
     * 나올 때까지 가입하면 된다.
     *
     * <p>⚠️ <b>이어 붙이던 때 이 비율이 100% 였다</b>(실측 2000/2000, 대조군 1.25%, 우연 기대
     * 1.5625%). 즉 솔트는 막으려던 공격을 막지 못했다. MAC 은 키 없이 충돌 관계를 계산할 수
     * 없으므로 그 전이가 우연 수준으로 내려간다.
     *
     * <p>상한만 단언한다 — 하한을 걸면 1/64 의 요동으로 플레이크가 된다. 여기서 가르려는 것은
     * "우연 수준" 과 "100%" 이고 그 둘은 자릿수가 다르다.
     */
    @Test
    @DisplayName("⭐ 한 인스턴스에서 찾은 충돌이 다른 인스턴스로 옮겨지지 않는다 — 조준의 본체다")
    void collisionsDoNotTransferBetweenInstances() {
        EnterRateLimiter attacker = limiter(64, 1_000, 1_000);
        EnterRateLimiter victim = limiter(64, 1_000, 1_000);

        int trials = 500;
        int transferred = 0;
        for (int t = 0; t < trials; t++) {
            String target = java.util.UUID.randomUUID().toString();
            int want = attacker.shardIndexFor(target);
            // 공격자 인스턴스에서 타깃과 같은 샤드인 후보를 찾는다(기대 64회 시행)
            String candidate;
            do {
                candidate = java.util.UUID.randomUUID().toString();
            } while (attacker.shardIndexFor(candidate) != want);

            if (victim.shardIndexFor(candidate) == victim.shardIndexFor(target)) {
                transferred++;
            }
        }

        assertThat(transferred)
                // ⚠️ AssertJ 의 as(fmt, args) 로 넘긴다. "..%d.." + "..".formatted(a, b) 로 쓰면
                // formatted 가 **뒤쪽 리터럴에만** 붙어(연산자 우선순위) 앞쪽 %d 가 그대로 찍힌다 —
                // 실측으로 그렇게 나왔고, 빨강을 보는 사람에게 정작 그 수가 안 보였다.
                .as("충돌이 인스턴스 간에 옮겨진다(%d/%d) — 우연 기대는 1/64 다. 배치가 키 혼합이"
                        + " 아니라 상수 변환이면 이 수가 분모에 붙는다", transferred, trials)
                .isLessThan(trials / 4);
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
                new EnterRateLimitProperties(permits, burst, permits, burst, shards, null, null, null),
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
                new EnterRateLimitProperties(1_000, 1_000, 1_000, 1_000, 64, null, null, null),
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
