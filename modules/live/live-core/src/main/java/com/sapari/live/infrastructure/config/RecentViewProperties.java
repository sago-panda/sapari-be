package com.sapari.live.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 최근 본 기록 실행기 크기. 미설정은 기본값, 0 이하는 부팅에서 막는다.
 *
 * @param threads       기록이 동시에 쥘 수 있는 커넥션 상한이기도 하다 — 올리면 DB 장애 중 상태 전이와 풀을 나눠 쓴다.
 * @param queueCapacity 넘치면 버리고 {@code live.recent_view.dropped{reason=rejected}} 로 센다. 회원 입장 버스트
 *                      ({@code live.enter.rate-limit.member-burst})와 같은 크기라, 큰 방송 시작에 rejected 가 이어지면
 *                      이 값을 먼저 올린다.
 */
@ConfigurationProperties("live.recent-view")
public record RecentViewProperties(Integer threads, Integer queueCapacity) {

    private static final int DEFAULT_THREADS = 2;
    private static final int DEFAULT_QUEUE_CAPACITY = 1_000;

    public RecentViewProperties {
        if (threads == null) {
            threads = DEFAULT_THREADS;
        }
        if (threads <= 0) {
            throw new IllegalArgumentException("live.recent-view.threads 는 양수여야 합니다: " + threads);
        }
        if (queueCapacity == null) {
            queueCapacity = DEFAULT_QUEUE_CAPACITY;
        }
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("live.recent-view.queue-capacity 는 양수여야 합니다: " + queueCapacity);
        }
    }
}
