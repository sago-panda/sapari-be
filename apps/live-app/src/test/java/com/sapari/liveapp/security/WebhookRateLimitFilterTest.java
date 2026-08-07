package com.sapari.liveapp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.FilterChain;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.sapari.liveapp.config.WebhookRateLimitConfig;

/**
 * webhook 경로 레이트리밋.
 *
 * <p>여기서 고정하는 건 셋이다: <b>한도를 넘기면 실제로 끊기는지</b>, <b>webhook 이 아닌 경로는 건드리지
 * 않는지</b>, <b>거부가 체인을 타지 않는지</b>(타면 서명 검증 비용을 그대로 치러 레이트리밋이 무의미해진다).
 */
class WebhookRateLimitFilterTest {

    private static final String WEBHOOK_URI = "/webhooks/livekit";

    private static WebhookRateLimitProperties props(int permitsPerSecond, int burst) {
        return new WebhookRateLimitProperties(permitsPerSecond, burst);
    }

    /** 체인이 몇 번 통과했는지 센다 — 거부된 요청이 뒤로 새는지 확인하는 게 핵심이다. */
    private static final class CountingChain implements FilterChain {
        private final AtomicInteger passed = new AtomicInteger();

        @Override
        public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
            passed.incrementAndGet();
        }
    }

    private MockHttpServletResponse send(WebhookRateLimitFilter filter, CountingChain chain, String uri)
            throws Exception {
        return sendWithContext(filter, chain, uri, "");
    }

    /** 원시(인코딩된) URI 를 그대로 넣는다 — 디코딩된 값을 넣으면 실제 컨테이너와 달라져 결함이 숨는다. */
    private MockHttpServletResponse sendWithContext(
            WebhookRateLimitFilter filter, CountingChain chain, String uri, String contextPath)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRequestURI(uri);
        request.setContextPath(contextPath);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    @DisplayName("버스트를 넘기면 429 로 끊고 체인을 타지 않는다 — 통과시키면 서명 검증 비용을 그대로 낸다")
    void rejectsOverBurst_withoutInvokingChain() throws Exception {
        WebhookRateLimitFilter filter = new WebhookRateLimitFilter(props(1, 2));
        CountingChain chain = new CountingChain();

        assertThat(send(filter, chain, WEBHOOK_URI).getStatus()).isEqualTo(200);
        assertThat(send(filter, chain, WEBHOOK_URI).getStatus()).isEqualTo(200);

        MockHttpServletResponse rejected = send(filter, chain, WEBHOOK_URI);

        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(rejected.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        // 거부된 요청은 체인을 타지 않아야 한다
        assertThat(chain.passed.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("퍼센트 인코딩한 경로도 똑같이 제한된다 — 원시 경로로 비교하면 여기서 통째로 뚫린다")
    void throttlesPercentEncodedPath() throws Exception {
        WebhookRateLimitFilter filter = new WebhookRateLimitFilter(props(1, 1));
        CountingChain chain = new CountingChain();

        // %77 = 'w'. 라우팅은 디코딩해서 매칭하므로 이 요청도 컨트롤러에 도달한다.
        assertThat(send(filter, chain, "/%77ebhooks/livekit").getStatus()).isEqualTo(200);
        assertThat(send(filter, chain, "/%77ebhooks/livekit").getStatus()).isEqualTo(429);

        // 인코딩 여부와 무관하게 같은 버킷을 써야 한다 — 따로 세면 버킷을 두 배로 쓸 수 있다
        assertThat(send(filter, chain, WEBHOOK_URI).getStatus()).isEqualTo(429);
        assertThat(chain.passed.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("context-path 가 붙어도 제한된다 — 붙는 순간 조용히 꺼지던 경로")
    void throttlesUnderContextPath() throws Exception {
        WebhookRateLimitFilter filter = new WebhookRateLimitFilter(props(1, 1));
        CountingChain chain = new CountingChain();

        assertThat(sendWithContext(filter, chain, "/live/webhooks/livekit", "/live").getStatus())
                .isEqualTo(200);
        assertThat(sendWithContext(filter, chain, "/live/webhooks/livekit", "/live").getStatus())
                .isEqualTo(429);
    }

    @Test
    @DisplayName("webhook 이 아닌 경로는 토큰을 쓰지도, 거부되지도 않는다")
    void doesNotThrottleOtherPaths() throws Exception {
        WebhookRateLimitFilter filter = new WebhookRateLimitFilter(props(1, 2));
        CountingChain chain = new CountingChain();

        for (int i = 0; i < 10; i++) {
            assertThat(send(filter, chain, "/api/v1/lives/123").getStatus()).isEqualTo(200);
        }
        assertThat(chain.passed.get()).isEqualTo(10);

        // 위 10건이 버킷을 비우지 않았어야 한다
        assertThat(send(filter, chain, WEBHOOK_URI).getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("시간이 지나면 다시 통과한다 — 한 번 넘기면 영구 차단되는 구조면 방이 Ready 에 갇힌다")
    void refillsOverTime() throws Exception {
        // 초당 100 개면 10ms 남짓이면 토큰 하나가 찬다
        WebhookRateLimitFilter filter = new WebhookRateLimitFilter(props(100, 100));
        CountingChain chain = new CountingChain();

        for (int i = 0; i < 100; i++) {
            send(filter, chain, WEBHOOK_URI);
        }
        assertThat(send(filter, chain, WEBHOOK_URI).getStatus()).isEqualTo(429);

        Thread.sleep(50);

        assertThat(send(filter, chain, WEBHOOK_URI).getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("거부 로그는 공격 규모와 무관하게 억제된다 — 건수 비율이면 볼륨을 공격자가 정한다")
    void rejectionLoggingIsRateIndependent() throws Exception {
        WebhookRateLimitFilter filter = new WebhookRateLimitFilter(props(1, 1));
        CountingChain chain = new CountingChain();
        Logger logger = (Logger) LoggerFactory.getLogger(WebhookRateLimitFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            send(filter, chain, WEBHOOK_URI); // 버킷 소진
            for (int i = 0; i < 5_000; i++) {
                send(filter, chain, WEBHOOK_URI);
            }
        } finally {
            logger.detachAppender(appender);
        }

        // 5,000 건을 거부해도 억제 간격(10s) 안이라 한 줄이어야 한다
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.getFirst().getFormattedMessage()).contains("누적");
    }

    @Test
    @DisplayName("burst 가 초당 허용량보다 작으면 부팅에서 막는다 — 지속 허용량이 설정값에 못 미친다")
    void rejectsBurstSmallerThanRate() {
        assertThatThrownBy(() -> props(20, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> props(20, 20)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("permits-per-second 0·음수는 부팅에서 막는다 — 정상 webhook 이 전량 차단된다")
    void rejectsNonPositiveRate() {
        assertThatThrownBy(() -> props(0, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> props(-1, 10)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("설정이 없으면 필터가 등록된다 — 미설정이 기본 상태다")
    void registeredByDefault() {
        rateLimitRunner().run(context -> assertThat(context).hasSingleBean(WebhookRateLimitFilter.class));
    }

    @Test
    @DisplayName("enabled=false 면 필터 빈 자체가 없다 — 빈이 남으면 체인에 등록돼 스위치가 무의미해진다")
    void disabledByProperty() {
        rateLimitRunner()
                .withPropertyValues("live.webhook.rate-limit.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(WebhookRateLimitFilter.class));
    }

    @Test
    @DisplayName("꺼져 있으면 잘못된 한도가 남아 있어도 부팅된다 — 스위치를 내리는 건 대개 말썽이 났을 때다")
    void disabledSwitchTolerateInvalidProperty() {
        rateLimitRunner()
                .withPropertyValues(
                        "live.webhook.rate-limit.enabled=false",
                        "live.webhook.rate-limit.permits-per-second=0")
                .run(context -> {
                    // 조건이 @Bean 에만 걸려 있으면 @EnableConfigurationProperties 가 살아 바인딩이 돌고,
                    // 껐는데도 낡은 값 하나가 부팅을 막는다.
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(WebhookRateLimitFilter.class);
                });
    }

    @Test
    @DisplayName("잘못된 한도는 부팅을 실패시킨다 — 조용히 기본값으로 뜨면 오설정이 드러나지 않는다")
    void invalidPropertyFailsStartup() {
        rateLimitRunner()
                .withPropertyValues("live.webhook.rate-limit.permits-per-second=0")
                .run(context -> assertThat(context).hasFailed());
    }

    private ApplicationContextRunner rateLimitRunner() {
        return new ApplicationContextRunner().withUserConfiguration(WebhookRateLimitConfig.class);
    }

    @Test
    @DisplayName("미설정이면 기본값으로 뜬다 — application*.yml 이 미추적이라 '설정 없음'이 정상 상태다")
    void appliesDefaultsWhenUnset() {
        WebhookRateLimitProperties defaults = new WebhookRateLimitProperties(null, null);

        assertThat(defaults.burst()).isGreaterThanOrEqualTo(defaults.permitsPerSecond());
        // 기본값은 정상 트래픽(방 하나당 몇 건)보다 두 자릿수 높아야 한다 — 그 부근으로 내리면
        // 미인증 공격자가 그 rps 만으로 정상 webhook 을 굶긴다. 낮춰 잡는 회귀를 여기서 막는다.
        assertThat(defaults.permitsPerSecond())
                .describedAs("기본 한도는 셰이퍼가 아니라 CPU 상한이어야 한다")
                .isGreaterThanOrEqualTo(100);
    }
}
