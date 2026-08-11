package com.sapari.liveapp.security;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.extern.slf4j.Slf4j;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * webhook 수신 경로의 레이트리밋. {@link WebhookBodySizeLimitFilter} 바로 다음에 선다 — 크기로 먼저 걸러야
 * 큰 본문이 토큰을 소비하지 않는다.
 *
 * <p><b>이 필터는 가용성 공격을 막지 못한다.</b> 막을 수 없다 — 서명을 검증하기 전에는 정상 요청과 위조
 * 요청을 구분할 방법이 없어서, 어떤 사전 게이트든 양쪽을 똑같이 때린다. 한도를 정상 트래픽 부근으로
 * 낮추면 공격자가 그 rps 만으로 토큰을 선점해 진짜 {@code ingress_started} 를 굶길 수 있다(방어가 아니라
 * 공격 도구가 된다). 그래서 한도는 <b>정상 트래픽이 절대 닿지 않는 높이</b>에 두고, 이 필터의 역할은
 * 최악의 CPU 소모에 상한을 거는 것 하나로 한정한다. rps 제한은 앞단(Ingress/WAF)의 몫이다.
 *
 * <p><b>실제 동시 처리량을 묶는 건 이 필터가 아니라 톰캣 스레드 풀이다.</b> 한도가 정상 트래픽보다
 * 두 자릿수 높으므로 평상시 버킷은 아무것도 제한하지 않는다. 그리고 {@code server.tomcat.*} 은 이
 * 저장소에서 볼 수 없다({@code application*.yml} 미추적) — "레이트리밋이 있으니 괜찮다"고 읽지 말 것.
 *
 * <p><b>IP 별이 아니라 전역 버킷인 이유.</b> {@code X-Forwarded-For} 는 공격자가 마음대로 채우는 헤더라
 * 그걸 키로 버킷 맵을 만들면 <b>레이트리밋 자체가 무제한 메모리 증식 경로</b>가 된다(요청마다 새 키).
 * 조작 불가능한 {@code getRemoteAddr()} 는 Ingress 뒤에서 전부 프록시 IP 하나라 전역과 다를 게 없다.
 *
 * <p>거부된 요청에 본문은 싣지 않는다 — 호출자가 기계(LiveKit)라 읽지 않고, 미인증 경로에서 내부 사정을
 * 알려줄 이유도 없다. 상태 코드와 {@code Retry-After} 로 충분하다.
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class WebhookRateLimitFilter extends OncePerRequestFilter {

    /**
     * 로그 억제 간격. <b>건수가 아니라 시간</b> 기준인 게 핵심이다 — "N 건마다 한 줄"은 비율이라
     * 로그 볼륨이 공격 규모에 비례한다(10k rps 면 초당 수십 줄). 시간 기준이면 상대가 얼마나 퍼붓든
     * 이 간격당 한 줄로 고정된다. 그 사이 건수는 누적값으로 한 번에 실어 정보는 잃지 않는다.
     */
    private static final long LOG_INTERVAL_NANOS = 10_000_000_000L; // 10s

    private final WebhookRateLimitProperties properties;
    private final TokenBucket bucket;
    private final AtomicLong rejected = new AtomicLong();
    /** 마지막으로 로그를 남긴 시각. 벽시계가 아니라 단조 시계다({@link TokenBucket} 과 같은 이유). */
    private final AtomicLong lastLogNanos = new AtomicLong(System.nanoTime() - LOG_INTERVAL_NANOS);

    public WebhookRateLimitFilter(WebhookRateLimitProperties properties) {
        this.properties = properties;
        this.bucket = new TokenBucket(properties.burst(), properties.permitsPerSecond());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!WebhookPaths.isWebhookRequest(request) || bucket.tryAcquire()) {
            filterChain.doFilter(request, response);
            return;
        }
        long count = rejected.incrementAndGet();
        if (shouldLog()) {
            // 한도가 정상 트래픽보다 두 자릿수 높으므로 이 로그가 뜬다는 건 평상시 트래픽이 아니다 —
            // 공격이거나 LiveKit 이상이다. 지표가 붙기 전까지는 이 한 줄이 유일한 신호다.
            log.error("webhook 레이트리밋 초과 — 정상 트래픽 수준이 아님(공격 또는 발신 측 이상). "
                            + "누적={}건, 한도={}/s(burst {})",
                    count, properties.permitsPerSecond(), properties.burst());
        }
        // 서블릿 API 에 429 상수가 없다 — 숫자 리터럴 대신 Spring 쪽 enum 을 쓴다
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(properties.retryAfter().toSeconds()));
    }

    /** 간격을 선점한 스레드 하나만 참을 받는다 — CAS 라 폭주 중에도 잠금 경합이 생기지 않는다. */
    private boolean shouldLog() {
        long now = System.nanoTime();
        long last = lastLogNanos.get();
        return now - last >= LOG_INTERVAL_NANOS && lastLogNanos.compareAndSet(last, now);
    }
}
