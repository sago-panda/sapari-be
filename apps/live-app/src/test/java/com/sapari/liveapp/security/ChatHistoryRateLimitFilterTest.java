package com.sapari.liveapp.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
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
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.sapari.liveapp.config.ChatHistoryRateLimitConfig;

/**
 * 공개 채팅 이력 조회 상한.
 *
 * <p>고정하는 것: 한도를 넘기면 <b>429 와 {@code Retry-After}</b> 가 나가는지, <b>거부가 체인을
 * 타지 않는지</b>(타면 Postgres 1 + Mongo 1 을 그대로 치러 상한이 무의미해진다), <b>{@code HEAD}
 * 도 세는지</b>(본문을 안 보내도 조회 비용은 같다), 이력이 아닌 경로는 건드리지 않는지,
 * 그리고 <b>미설정은 기본값 · 잘못된 값은 부팅 차단</b>인지.
 */
class ChatHistoryRateLimitFilterTest {

    private static final String HISTORY_URI = "/api/v1/lives/" + UUID.randomUUID() + "/chat/history";

    /** 멈춘 시계 — 가장 느린 버킷도 초당 1개는 차서 실시계면 "비었다" 단정의 창이 1초다. */
    private static ChatHistoryRateLimitFilter filter(int permits, int burst) {
        ChatHistoryRateLimitProperties properties = new ChatHistoryRateLimitProperties();
        properties.setPermitsPerSecond(permits);
        properties.setBurst(burst);
        return new ChatHistoryRateLimitFilter(new ChatHistoryRateLimiter(properties, () -> 0L));
    }

    /** 체인이 몇 번 통과했는지 센다 — 거부된 요청이 뒤로 새는지가 핵심이다. */
    private static final class CountingChain implements FilterChain {
        private final AtomicInteger passed = new AtomicInteger();

        @Override
        public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
            passed.incrementAndGet();
        }
    }

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        return request;
    }

    @Test
    @DisplayName("⭐ 한도를 넘기면 429 와 Retry-After 가 나가고 체인을 타지 않는다")
    void overTheLimitIsRejectedWithoutTouchingTheChain() throws Exception {
        // given: 한도 1 — 두 번째 요청은 토큰이 없다
        ChatHistoryRateLimitFilter filter = filter(1, 1);
        CountingChain chain = new CountingChain();

        // when: 첫 요청은 통과
        MockHttpServletResponse first = new MockHttpServletResponse();
        filter.doFilter(request("GET", HISTORY_URI), first, chain);

        // then
        assertThat(first.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(chain.passed.get()).isEqualTo(1);

        // when: 두 번째는 거부
        MockHttpServletResponse second = new MockHttpServletResponse();
        filter.doFilter(request("GET", HISTORY_URI), second, chain);

        // then: 상태코드와 Retry-After. 그리고 체인 통과 수가 늘지 않아야 한다 —
        // 늘면 거부해 놓고 조회 비용은 그대로 치른 것이다.
        assertThat(second.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(second.getHeader(HttpHeaders.RETRY_AFTER))
                .as("Retry-After 가 없으면 클라가 간격을 모르고 즉시 재시도한다").isEqualTo("1");
        assertThat(chain.passed.get())
                .as("거부된 요청이 체인으로 샜다 — Postgres 1 + Mongo 1 을 그대로 치른다").isEqualTo(1);
    }

    @Test
    @DisplayName("⭐ HEAD 도 센다 — 본문을 안 보내도 조회 비용(Postgres 1 + Mongo 1)은 같다")
    void headCountsAgainstTheLimit() throws Exception {
        // given: 한도 1 을 GET 하나로 소진한다
        ChatHistoryRateLimitFilter filter = filter(1, 1);
        CountingChain chain = new CountingChain();
        filter.doFilter(request("GET", HISTORY_URI), new MockHttpServletResponse(), chain);

        // when: 같은 경로의 HEAD
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("HEAD", HISTORY_URI), response, chain);

        // then: 메서드를 GET 으로만 보면 HEAD 가 상한 밖으로 빠진다
        assertThat(response.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(chain.passed.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("이력이 아닌 경로는 건드리지 않는다 — 형제 경로에 상한을 걸면 비용 없는 길을 막는다")
    void otherPathsAreUntouched() throws Exception {
        // given: 한도 1 을 이미 소진했다
        ChatHistoryRateLimitFilter filter = filter(1, 1);
        CountingChain chain = new CountingChain();
        filter.doFilter(request("GET", HISTORY_URI), new MockHttpServletResponse(), chain);

        // when & then: 입장·목록·다시보기, 그리고 접미만 같은 경로
        for (String uri : new String[]{
                "/api/v1/lives/rooms",
                "/api/v1/lives/rooms/" + UUID.randomUUID(),
                "/api/v1/lives/rooms/" + UUID.randomUUID() + "/replay",
                "/api/v1/lives/" + UUID.randomUUID() + "/extra/chat/history"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request("GET", uri), response, chain);
            assertThat(response.getStatus()).as(uri).isEqualTo(HttpStatus.OK.value());
        }
        assertThat(chain.passed.get()).isEqualTo(5);
    }

    /**
     * 거부 로그가 솎이는지, 그리고 <b>누적 수가 언제 보이는지</b>.
     *
     * <p>⚠️ 처음 이 칸을 "한 줄이 최종 누적을 싣는다" 로 적었는데 거짓이었다 — 첫 거부가 즉시
     * 로그되므로 그 줄은 <b>그 시점의 수(1)</b>를 싣고, 뒤따르는 거부는 억제된다. 규모는
     * <b>다음 창의 줄</b>에서 보인다. 형제들({@code EntryGate} · {@code ChatSessionRegistry})도
     * 같은 성질이고, 그래서 "누적 수를 실어 규모를 잃지 않는다" 는 문장은 <b>두 번째 창부터</b>
     * 참이다. 리미터 javadoc 을 그에 맞춰 고쳤다.
     *
     * <p>그래서 시계를 움직여 두 창을 본다 — 멈춘 시계로는 그 사실이 드러나지 않는다.
     */
    @Test
    @DisplayName("⭐ 거부 로그는 솎이고, 누적 수는 다음 창의 줄에서 보인다")
    void rejectionLogIsThrottledAndScaleAppearsInTheNextWindow() throws Exception {
        // given: 움직일 수 있는 시계
        long[] now = {0L};
        ChatHistoryRateLimitProperties properties = new ChatHistoryRateLimitProperties();
        properties.setPermitsPerSecond(1);
        properties.setBurst(1);
        ChatHistoryRateLimitFilter filter =
                new ChatHistoryRateLimitFilter(new ChatHistoryRateLimiter(properties, () -> now[0]));
        CountingChain chain = new CountingChain();
        Logger logger = (Logger) LoggerFactory.getLogger(ChatHistoryRateLimiter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            // when: 같은 창에서 20회 — 1회 통과, 19회 거부
            for (int i = 0; i < 20; i++) {
                filter.doFilter(request("GET", HISTORY_URI), new MockHttpServletResponse(), chain);
            }

            // then: 줄은 하나이고, 그 줄은 **첫 거부 시점의 수**를 싣는다
            assertThat(appender.list)
                    .as("거부마다 한 줄이면 로그 I/O 가 폭주 규모에 비례한다").hasSize(1);
            assertThat(appender.list.getFirst().getFormattedMessage())
                    .contains("공개 채팅 이력 조회 상한")
                    .as("첫 줄은 최종 누적이 아니라 그 시점의 수다").contains("누적 1건");

            // when: 창을 넘긴다(10초). 토큰도 차지만 버스트가 1 이라 두 번째 요청부터 다시 거부다.
            now[0] += java.time.Duration.ofSeconds(11).toNanos();
            filter.doFilter(request("GET", HISTORY_URI), new MockHttpServletResponse(), chain);
            filter.doFilter(request("GET", HISTORY_URI), new MockHttpServletResponse(), chain);

            // then: 두 번째 줄이 그동안 쌓인 규모를 싣는다
            assertThat(appender.list).hasSize(2);
            assertThat(appender.list.get(1).getFormattedMessage())
                    .as("다음 창의 줄에도 규모가 없으면 억제된 구간이 통째로 안 보인다")
                    .contains("누적 20건");
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("⭐ 미설정은 기본값으로 뜨고 잘못된 값은 부팅을 막는다")
    void unsetBootsAndInvalidFailsBoot() {
        ApplicationContextRunner runner =
                new ApplicationContextRunner().withUserConfiguration(ChatHistoryRateLimitConfig.class);

        // 미설정 → 기본값으로 뜬다
        runner.run(context -> assertThat(context)
                .as("미설정이 부팅을 막으면 설정 없이 뜨는 것이 기본값이어야 한다는 규율이 깨진다")
                .hasNotFailed());

        // 0 → 막힌다. 조용히 기본값으로 떨어지면 "설정했다고 믿는데 안 걸린 파드" 가 된다.
        runner.withPropertyValues("live.chat.history.rate-limit.permits-per-second=0")
                .run(context -> assertThat(context).hasFailed());

        // burst < permits → 막힌다. 버킷이 보충량보다 작으면 지속 허용량이 설정값에 못 미친다.
        runner.withPropertyValues(
                        "live.chat.history.rate-limit.permits-per-second=20",
                        "live.chat.history.rate-limit.burst=5")
                .run(context -> assertThat(context).hasFailed());
    }
}
