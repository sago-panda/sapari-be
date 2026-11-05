package com.sapari.liveapp.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.live.application.port.EnterPopulation;

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
        CountingEnterMetrics metrics = new CountingEnterMetrics();
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
}
