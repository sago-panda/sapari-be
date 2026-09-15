package com.sapari.liveapp.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * webhook 수신 경로의 레이트리밋 정책.
 *
 * <p>미설정은 허용하고(기본값) 잘못된 값은 부팅에서 막는다 — {@code application*.yml} 이 추적되지 않아
 * "설정 없음"이 정상 상태다. 다만 0 이나 음수로 뜨면 정상 webhook 이 전량 차단되어 방이 Ready 에 갇힌다.
 *
 * <p>켜고 끄는 {@code enabled} 는 <b>이 레코드에 없다</b> — 스위치는 {@code WebhookRateLimitConfig} 의
 * {@code @ConditionalOnProperty} 가 원시 프로퍼티로 직접 읽는다. 여기에 같은 이름의 필드를 두면 아무도
 * 읽지 않는 값이 생겨, 나중에 이걸 보고 "스위치가 여기 있다"고 오해하게 된다.
 */
@ConfigurationProperties("live.webhook.rate-limit")
public record WebhookRateLimitProperties(
        Integer permitsPerSecond,
        Integer burst
) {
    /**
     * <b>정상 트래픽 부근으로 내리지 말 것.</b> 이건 트래픽 셰이퍼가 아니라 최악의 CPU 소모에 거는
     * 상한이다. 실측상 서명 검증 1건은 본문 512B 에서 약 4.5µs(코어당 22만 건/s), 본문이 상한(64KB)까지
     * 찬 최악에서도 약 425µs(코어당 2,300건/s)다. 정상 webhook 은 방 하나당 몇 건 수준이라 이 값의
     * 100 분의 1 도 쓰지 않는다.
     *
     * <p>낮게 잡고 싶은 유혹이 있지만("초당 몇 건인데 500 은 과하다"), 이 경로는 {@code permitAll} 이라
     * <b>한도가 곧 미인증 공격자의 비용</b>이다. 20/s 로 두면 누구나 20 rps 만으로 토큰을 선점해 진짜
     * {@code ingress_started} 를 429 로 떨어뜨릴 수 있다 — 방어가 아니라 가용성 공격 도구가 된다.
     * 500 rps × 64KB = 32MB/s 는 이미 네트워크 계층 공격이고, 그건 앞단(Ingress/WAF)의 몫이다.
     */
    private static final int DEFAULT_PERMITS_PER_SECOND = 500;
    /** 롤링 배포 후 밀린 재전송이 한꺼번에 도착하는 것을 정상 트래픽으로 흡수하기 위한 여유. */
    private static final int DEFAULT_BURST = 1_000;

    public WebhookRateLimitProperties {
        if (permitsPerSecond == null) {
            permitsPerSecond = DEFAULT_PERMITS_PER_SECOND;
        }
        if (burst == null) {
            burst = DEFAULT_BURST;
        }
        if (permitsPerSecond <= 0) {
            throw new IllegalArgumentException(
                    "live.webhook.rate-limit.permits-per-second 는 양수여야 합니다: " + permitsPerSecond);
        }
        if (burst < permitsPerSecond) {
            // 버킷이 초당 보충량보다 작으면 채워지는 즉시 넘쳐 지속 허용량이 설정값에 못 미친다.
            throw new IllegalArgumentException(
                    "live.webhook.rate-limit.burst 는 permits-per-second 이상이어야 합니다: "
                            + burst + " < " + permitsPerSecond);
        }
    }

    /**
     * 429 응답의 {@code Retry-After} 에 실을 값.
     *
     * <p>토큰 하나가 차는 실제 시간(기본값에서 2ms)보다 훨씬 길게 잡는다 — 한도까지 갔다는 건 이미
     * 비정상 상황이라, 발신자를 곧바로 되돌려 보내는 것보다 한 박자 쉬게 하는 편이 낫다. 초 단위
     * 헤더라 1 미만은 표현할 수도 없다.
     */
    public Duration retryAfter() {
        return Duration.ofSeconds(1);
    }
}
