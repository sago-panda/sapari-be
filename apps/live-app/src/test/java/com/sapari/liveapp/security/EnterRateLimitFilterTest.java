package com.sapari.liveapp.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.FilterChain;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.sapari.liveapp.config.EnterRateLimitConfig;

/**
 * 입장 상한 — 필터.
 *
 * <p>고정하는 것: <b>한도를 넘기면 429 와 {@code Retry-After} 가 나가는지</b>, <b>거부가 체인을
 * 타지 않는지</b>(타면 DB 읽기와 RS256 서명을 그대로 치러 상한이 무의미해진다), <b>두 집단이
 * 서로를 굶히지 않는지</b>, <b>인증 쪽에도 상한이 있는지</b>, <b>입장이 아닌 경로는 건드리지
 * 않는지</b>, <b>거부 로그가 집단별로 뜨는지</b>, 그리고 <b>기본값과 부팅 가드</b>.
 */
class EnterRateLimitFilterTest {

    private static final String ENTER_URI = "/api/v1/lives/rooms/" + UUID.randomUUID();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static EnterRateLimitProperties props(int guest, int member) {
        return new EnterRateLimitProperties(guest, guest, member, member);
    }

    /** 멈춘 시계 — 가장 느린 버킷도 초당 1개는 차서 실시계면 "비었다" 단정의 창이 1초다. */
    private static EnterRateLimitFilter filter(EnterRateLimitProperties properties) {
        return new EnterRateLimitFilter(new EnterRateLimiter(properties, () -> 0L));
    }

    /** 체인이 몇 번 통과했는지 센다 — 거부된 요청이 뒤로 새는지가 핵심이다. */
    private static final class CountingChain implements FilterChain {
        private final AtomicInteger passed = new AtomicInteger();

        @Override
        public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
            passed.incrementAndGet();
        }
    }

    private static MockHttpServletRequest get(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        return request;
    }

    private static void authenticate() {
        LiveUserPrincipal principal =
                new LiveUserPrincipal(UUID.randomUUID(), "USER", "닉", "a@b.c");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, java.util.List.of()));
    }

    @Test
    @DisplayName("⭐ 한도를 넘기면 429 와 Retry-After 가 나가고 체인을 타지 않는다")
    void aRejectedEntryGets429AndNeverReachesTheChain() throws Exception {
        EnterRateLimitFilter filter = filter(props(1, 1));
        CountingChain chain = new CountingChain();

        filter.doFilter(get(ENTER_URI), new MockHttpServletResponse(), chain);
        MockHttpServletResponse rejected = new MockHttpServletResponse();
        filter.doFilter(get(ENTER_URI), rejected, chain);

        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(rejected.getHeader(HttpHeaders.RETRY_AFTER))
                .as("지터가 붙으므로 고정값이 아니다 — 1~3초 범위")
                .isNotNull();
        assertThat(Long.parseLong(rejected.getHeader(HttpHeaders.RETRY_AFTER))).isBetween(1L, 3L);
        assertThat(chain.passed.get())
                .as("거부가 체인을 타면 DB 읽기와 RS256 서명을 그대로 치른다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("⭐ 두 집단이 서로를 굶기지 않는다 — 버킷이 둘인 이유")
    void theTwoPopulationsDoNotStarveEachOther() throws Exception {
        EnterRateLimitFilter filter = filter(props(1, 1));
        CountingChain chain = new CountingChain();

        filter.doFilter(get(ENTER_URI), new MockHttpServletResponse(), chain);
        MockHttpServletResponse guestRejected = new MockHttpServletResponse();
        filter.doFilter(get(ENTER_URI), guestRejected, chain);
        assertThat(guestRejected.getStatus()).isEqualTo(429);

        authenticate();
        MockHttpServletResponse memberFirst = new MockHttpServletResponse();
        filter.doFilter(get(ENTER_URI), memberFirst, chain);
        assertThat(memberFirst.getStatus())
                .as("게스트 버킷이 빈 상태에서 회원이 막히면 두 버킷이 하나로 동작하는 것이다")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("⭐ 인증 입장에도 상한이 있다 — 가입 1회로 TTL 동안 무제한이 되지 않는다")
    void anAuthenticatedEntryIsAlsoCapped() throws Exception {
        EnterRateLimitFilter filter = filter(props(1, 1));
        CountingChain chain = new CountingChain();
        authenticate();

        filter.doFilter(get(ENTER_URI), new MockHttpServletResponse(), chain);
        MockHttpServletResponse rejected = new MockHttpServletResponse();
        filter.doFilter(get(ENTER_URI), rejected, chain);

        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(chain.passed.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("⭐ 입장이 아닌 경로는 상한을 쓰지 않는다 — 룸 토큰을 찍지 않는 형제 경로")
    void siblingPathsAreNotCapped() throws Exception {
        EnterRateLimitFilter filter = filter(props(1, 1));
        CountingChain chain = new CountingChain();

        // 목록·다시보기·POST 는 룸 토큰을 찍지 않는다. 접두사만 보면 전부 걸린다.
        for (String uri : java.util.List.of(
                "/api/v1/lives/rooms",
                "/api/v1/lives/rooms/",
                ENTER_URI + "/replay")) {
            for (int i = 0; i < 5; i++) {
                MockHttpServletResponse response = new MockHttpServletResponse();
                filter.doFilter(get(uri), response, chain);
                assertThat(response.getStatus())
                        .as("상한이 걸리면 막는 비용이 없는 경로를 막는다: %s", uri)
                        .isEqualTo(200);
            }
        }
        MockHttpServletRequest post = new MockHttpServletRequest("POST", ENTER_URI);
        post.setRequestURI(ENTER_URI);
        MockHttpServletResponse postResponse = new MockHttpServletResponse();
        filter.doFilter(post, postResponse, chain);
        assertThat(postResponse.getStatus()).isEqualTo(200);
    }

    /**
     * ⭐ <b>거부 로그가 집단별로 뜬다.</b>
     *
     * <p>게이트가 하나였을 때는 게스트 폭주가 창을 선점해 회원 줄이 거의 안 떴다(실측
     * 0/600). 그 상황이 바로 이 상한이 존재하는 이유이므로, 그때 "로그인한 실사용자가 거부되고
     * 있다" 가 안 보이면 가장 중요한 신호를 잃는다. 그리고 한 줄에 양쪽 누적을 같이 싣는다 —
     * 운영 지시가 "반대쪽 집단 수치와 비교" 라서 한 줄만 봐도 비율이 보여야 한다.
     */
    @Test
    @DisplayName("⭐ 거부 로그가 집단별로 뜨고 양쪽 누적을 같이 싣는다")
    void rejectionLogsAreThrottledPerPopulation() throws Exception {
        EnterRateLimitFilter filter = filter(props(1, 1));
        CountingChain chain = new CountingChain();
        Logger logger = (Logger) LoggerFactory.getLogger(EnterRateLimiter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            filter.doFilter(get(ENTER_URI), new MockHttpServletResponse(), chain);
            for (int i = 0; i < 20; i++) {
                filter.doFilter(get(ENTER_URI), new MockHttpServletResponse(), chain);
            }
            authenticate();
            filter.doFilter(get(ENTER_URI), new MockHttpServletResponse(), chain);
            for (int i = 0; i < 20; i++) {
                filter.doFilter(get(ENTER_URI), new MockHttpServletResponse(), chain);
            }

            assertThat(appender.list)
                    .as("게이트가 하나면 게스트가 창을 선점해 회원 줄이 안 뜬다")
                    .hasSize(2);
            assertThat(appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage).toList())
                    .anySatisfy(m -> assertThat(m).startsWith("게스트"))
                    .anySatisfy(m -> assertThat(m).startsWith("회원"))
                    .allSatisfy(m -> assertThat(m)
                            .as("한 줄만 봐도 비율이 보여야 운영 지시가 실행 가능하다")
                            .contains("게스트=").contains("회원="));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @DisplayName("기본값은 500/s · burst 1000 이고, 잘못된 값은 부팅에서 막는다")
    void defaultsAreFixedAndInvalidLimitsRefuseToBoot() {
        EnterRateLimitProperties defaults = new EnterRateLimitProperties(null, null, null, null);
        // 세 면(코드·yaml·AGENTS.md)에 같은 수가 적혀 있다 — 한 면만 고치면 여기서 깨진다.
        assertThat(defaults.guestPermitsPerSecond()).isEqualTo(500);
        assertThat(defaults.guestBurst()).isEqualTo(1_000);
        assertThat(defaults.memberPermitsPerSecond()).isEqualTo(500);
        assertThat(defaults.memberBurst()).isEqualTo(1_000);

        for (String[] bad : new String[][] {
                {"live.enter.rate-limit.guest-permits-per-second=0"},
                {"live.enter.rate-limit.member-permits-per-second=0"},
                {"live.enter.rate-limit.guest-permits-per-second=100",
                        "live.enter.rate-limit.guest-burst=99"},
                {"live.enter.rate-limit.member-permits-per-second=100",
                        "live.enter.rate-limit.member-burst=99"}}) {
            new ApplicationContextRunner()
                    .withUserConfiguration(EnterRateLimitConfig.class)
                    .withPropertyValues(bad)
                    .run(context -> assertThat(context).hasFailed());
        }
        new ApplicationContextRunner()
                .withUserConfiguration(EnterRateLimitConfig.class)
                .run(context -> assertThat(context)
                        .hasSingleBean(EnterRateLimiter.class)
                        .hasSingleBean(EnterRateLimitFilter.class));
    }
}
