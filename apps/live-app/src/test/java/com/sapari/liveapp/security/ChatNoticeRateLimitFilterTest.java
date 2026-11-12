package com.sapari.liveapp.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.FilterChain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.sapari.liveapp.config.ChatHistoryRateLimitConfig;
import com.sapari.liveapp.config.ChatNoticeRateLimitConfig;

/**
 * 공개 현재 공지 조회 상한. 형제 {@code ChatHistoryRateLimitFilterTest} 와 같은 축에 하나를 더 본다 —
 * <b>공지 버킷과 이력 버킷이 서로를 막지 않는지.</b> 합치면 입장 몰림이 다시보기 이력을 막는다.
 */
class ChatNoticeRateLimitFilterTest {

    private static final String NOTICE_URI = "/api/v1/lives/" + UUID.randomUUID() + "/chat/notice";
    private static final String HISTORY_URI = "/api/v1/lives/" + UUID.randomUUID() + "/chat/history";

    /** 멈춘 시계 — 형제와 같은 이유. */
    private static ChatNoticeRateLimitFilter noticeFilter(int permits, int burst) {
        ChatNoticeRateLimitProperties properties = new ChatNoticeRateLimitProperties();
        properties.setPermitsPerSecond(permits);
        properties.setBurst(burst);
        return new ChatNoticeRateLimitFilter(new ChatNoticeRateLimiter(properties, () -> 0L));
    }

    private static ChatHistoryRateLimitFilter historyFilter(int permits, int burst) {
        ChatHistoryRateLimitProperties properties = new ChatHistoryRateLimitProperties();
        properties.setPermitsPerSecond(permits);
        properties.setBurst(burst);
        return new ChatHistoryRateLimitFilter(new ChatHistoryRateLimiter(properties, () -> 0L));
    }

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
        // given: 한도 1
        ChatNoticeRateLimitFilter filter = noticeFilter(1, 1);
        CountingChain chain = new CountingChain();
        filter.doFilter(request("GET", NOTICE_URI), new MockHttpServletResponse(), chain);

        // when: 두 번째 요청
        MockHttpServletResponse second = new MockHttpServletResponse();
        filter.doFilter(request("GET", NOTICE_URI), second, chain);

        // then: 거부된 요청이 체인으로 새면 Postgres 2(방 조회 + 강퇴 목록) + Mongo 1 을 그대로 치른다
        assertThat(second.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(second.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(chain.passed.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("⭐ HEAD 도 센다 — 본문을 안 보내도 조회 비용은 같다")
    void headCountsAgainstTheLimit() throws Exception {
        // given: 한도 1 을 GET 으로 소진
        ChatNoticeRateLimitFilter filter = noticeFilter(1, 1);
        CountingChain chain = new CountingChain();
        filter.doFilter(request("GET", NOTICE_URI), new MockHttpServletResponse(), chain);

        // when
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("HEAD", NOTICE_URI), response, chain);

        // then
        assertThat(response.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(chain.passed.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("⭐ 공지 버킷이 바닥나도 이력은 지난다")
    void anExhaustedNoticeBucketDoesNotBlockHistory() throws Exception {
        // given: 두 필터를 실제 순서(이력 → 공지)로 세우고 둘 다 한도 1
        ChatHistoryRateLimitFilter history = historyFilter(1, 1);
        ChatNoticeRateLimitFilter notice = noticeFilter(1, 1);
        CountingChain chain = new CountingChain();
        FilterChain both = (req, res) -> notice.doFilter(req, res, chain);

        // when: 공지를 소진한다
        history.doFilter(request("GET", NOTICE_URI), new MockHttpServletResponse(), both);
        MockHttpServletResponse noticeRejected = new MockHttpServletResponse();
        history.doFilter(request("GET", NOTICE_URI), noticeRejected, both);
        MockHttpServletResponse historyPassed = new MockHttpServletResponse();
        history.doFilter(request("GET", HISTORY_URI), historyPassed, both);

        // then: 공지는 막혔고 이력은 지난다 — 합쳐지면 입장 몰림이 다시보기 이력을 막는다
        assertThat(noticeRejected.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(historyPassed.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(chain.passed.get()).as("공지 1 + 이력 1 만 통과").isEqualTo(2);
    }

    @Test
    @DisplayName("⭐ 이력 버킷이 바닥나도 공지는 지난다")
    void anExhaustedHistoryBucketDoesNotBlockNotice() throws Exception {
        // given: 두 필터를 실제 순서(이력 → 공지)로 세우고 둘 다 한도 1
        ChatHistoryRateLimitFilter history = historyFilter(1, 1);
        ChatNoticeRateLimitFilter notice = noticeFilter(1, 1);
        CountingChain chain = new CountingChain();
        FilterChain both = (req, res) -> notice.doFilter(req, res, chain);

        // when: 이력을 소진한다
        history.doFilter(request("GET", HISTORY_URI), new MockHttpServletResponse(), both);
        MockHttpServletResponse historyRejected = new MockHttpServletResponse();
        history.doFilter(request("GET", HISTORY_URI), historyRejected, both);
        MockHttpServletResponse noticePassed = new MockHttpServletResponse();
        history.doFilter(request("GET", NOTICE_URI), noticePassed, both);

        // then: 이력은 막혔고 공지는 지난다 — 다시보기 폭주가 입장 공지를 막지 않는다
        assertThat(historyRejected.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(noticePassed.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(chain.passed.get()).as("이력 1 + 공지 1 만 통과").isEqualTo(2);
    }

    @Test
    @DisplayName("공지가 아닌 경로는 건드리지 않는다")
    void otherPathsAreUntouched() throws Exception {
        // given: 한도 1 을 이미 소진
        ChatNoticeRateLimitFilter filter = noticeFilter(1, 1);
        CountingChain chain = new CountingChain();
        filter.doFilter(request("GET", NOTICE_URI), new MockHttpServletResponse(), chain);

        // when & then
        for (String uri : new String[]{
                "/api/v1/lives/rooms/" + UUID.randomUUID(),
                HISTORY_URI,
                "/api/v1/lives/" + UUID.randomUUID() + "/extra/chat/notice"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request("GET", uri), response, chain);
            assertThat(response.getStatus()).as(uri).isEqualTo(HttpStatus.OK.value());
        }
        assertThat(chain.passed.get()).isEqualTo(4);
    }

    @Test
    @DisplayName("⭐ 미설정은 기본값으로 뜨고 잘못된 값은 부팅을 막는다")
    void unsetBootsAndInvalidFailsBoot() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(ChatNoticeRateLimitConfig.class, ChatHistoryRateLimitConfig.class);

        runner.run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues("live.chat.notice.rate-limit.permits-per-second=0")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues(
                        "live.chat.notice.rate-limit.permits-per-second=20",
                        "live.chat.notice.rate-limit.burst=5")
                .run(context -> assertThat(context).hasFailed());
    }
}
