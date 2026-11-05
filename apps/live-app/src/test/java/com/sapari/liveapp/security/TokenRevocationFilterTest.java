package com.sapari.liveapp.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.sapari.common.securityjwt.store.AccessTokenRevocationChecker;
import com.sapari.common.securityjwt.store.SessionRevocationChecker;
import com.sapari.live.application.port.LiveMetrics;
import com.sapari.live.application.port.TokenRevocationAxis;

/**
 * 폐기 조회 — 필터.
 *
 * <p>고정하는 것: <b>폐기된 토큰의 인증이 거둬지는지</b>(안 거둬지면 로그아웃·차단된 토큰이
 * 자기 {@code exp} 까지 룸 토큰을 새로 받아 간다), <b>두 축이 각자 거두고 각자 세어지는지</b>
 * ({@code jti} 는 그 토큰만, {@code sid} 는 그 세션 전부), <b>조회 실패가 503 으로 끊기는지</b>,
 * <b>게스트는 조회하지 않는지</b>, <b>실패 로그가 솎이는지</b>, 그리고 <b>상한에 거부된 입장이
 * 조회를 치지 않는지</b> — 마지막 칸이 이 필터를 JWT 필터가 아니라 상한 뒤에 둔 이유 전부다.
 *
 * <p>공개 경로에서 그 결과가 <b>무엇으로 보이는지</b>는 {@code RevocationOnPublicRouteTest} 가
 * 잰다. 이 파일만으로는 분모가 빈다 — 인증이 거둬지는 것과 그 경로의 응답은 다른 축이다.
 */
class TokenRevocationFilterTest {

    private static final String ENTER_URI = "/api/v1/lives/rooms/" + UUID.randomUUID();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** 호출 수를 세는 가짜 — "몇 번 저장소를 쳤나" 가 이 파일의 두 번째 축이다. */
    private static final class CountingChecker
            implements AccessTokenRevocationChecker, SessionRevocationChecker {
        private final AtomicInteger calls = new AtomicInteger();
        private final boolean revoked;
        private final boolean throwing;

        private CountingChecker(boolean revoked, boolean throwing) {
            this.revoked = revoked;
            this.throwing = throwing;
        }

        static CountingChecker active() {
            return new CountingChecker(false, false);
        }

        static CountingChecker revoked() {
            return new CountingChecker(true, false);
        }

        static CountingChecker broken() {
            return new CountingChecker(false, true);
        }

        @Override
        public boolean isRevoked(UUID id) {
            calls.incrementAndGet();
            if (throwing) {
                throw new IllegalStateException("Redis down");
            }
            return revoked;
        }
    }

    private static final class CountingChain implements FilterChain {
        private final AtomicInteger passed = new AtomicInteger();

        @Override
        public void doFilter(ServletRequest req, ServletResponse res) {
            passed.incrementAndGet();
        }
    }

    private static MockHttpServletRequest get(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        return request;
    }

    /** 멈춘 시계 — 로그 억제 단정이 CI 부하에서 플레이크가 되지 않게 한다(형제와 같은 이음). */
    private static TokenRevocationFilter filter(AccessTokenRevocationChecker token,
            SessionRevocationChecker session, LiveMetrics metrics) {
        return new TokenRevocationFilter(token, session, metrics, () -> 0L);
    }

    private static void authenticate() {
        LiveUserPrincipal principal = new LiveUserPrincipal(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "SELLER", "닉", "a@b.c");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private static boolean authenticated() {
        return SecurityContextHolder.getContext().getAuthentication() != null;
    }

    /**
     * ⭐ <b>폐기된 토큰의 인증이 거둬지고, 그 사실이 축 태그와 함께 세어진다.</b>
     *
     * <p>이 칸이 깨지면 로그아웃·차단된 토큰이 자기 {@code exp} 까지 인증을 통과하고, 그 사이
     * 입장이 <b>룸 토큰을 새로 발급</b>해 폐기 전 역할이 담긴 채팅 입장권이 만들어진다.
     *
     * <p>지표를 같이 단언하는 이유: 적중은 거부 응답을 만들지 않으므로(공개 경로는 게스트로
     * 내려가고 역할 요구 경로만 401) 바깥에서 "폐기 조회가 꺼진 것" 과 "폐기가 없는 것" 이
     * 똑같이 보인다. 그 둘을 가르는 것이 이 수뿐이다.
     */
    @Test
    @DisplayName("⭐ 폐기된 토큰(jti)의 인증을 거두고 axis=JTI 로 센다 — 체인은 계속 탄다")
    void aRevokedTokenLosesItsAuthentication() throws Exception {
        CountingLiveMetrics metrics = new CountingLiveMetrics();
        TokenRevocationFilter filter =
                filter(CountingChecker.revoked(), CountingChecker.active(), metrics);
        CountingChain chain = new CountingChain();
        authenticate();

        filter.doFilter(get(ENTER_URI), new MockHttpServletResponse(), chain);

        assertThat(authenticated())
                .as("거두지 않으면 폐기된 토큰이 룸 토큰을 새로 받아 간다")
                .isFalse();
        assertThat(chain.passed.get())
                .as("401 본문은 체인 끝의 EntryPoint 가 만든다 — 여기서 끊으면 봉투가 빈다")
                .isEqualTo(1);
        assertThat(metrics.revocationHits).containsExactly(TokenRevocationAxis.JTI);
    }

    /**
     * ⭐ <b>두 축은 다른 사건이다.</b> {@code jti} 는 그 토큰 한 장, {@code sid} 는 그 로그인
     * 세션의 모든 토큰을 접는다. 세션 축만 폐기된 경우를 따로 재지 않으면 {@code sid} 조회가
     * 사실상 죽은 코드가 되고, "전 기기 로그아웃" 이 live-app 에만 안 먹는다.
     */
    @Test
    @DisplayName("⭐ 세션(sid)만 폐기돼도 거두고 axis=SID 로 센다 — 전 기기 로그아웃이 여기도 먹는다")
    void aRevokedSessionAlsoLosesItsAuthentication() throws Exception {
        CountingLiveMetrics metrics = new CountingLiveMetrics();
        TokenRevocationFilter filter =
                filter(CountingChecker.active(), CountingChecker.revoked(), metrics);
        authenticate();

        filter.doFilter(get(ENTER_URI), new MockHttpServletResponse(), new CountingChain());

        assertThat(authenticated()).isFalse();
        assertThat(metrics.revocationHits)
                .as("두 축을 한 수로 합치면 대량 로그아웃과 일상 재발급이 같아 보인다")
                .containsExactly(TokenRevocationAxis.SID);
    }

    /**
     * 토큰 축이 이미 폐기면 세션 축을 묻지 않는다 — 조회 하나가 왕복 하나다. 거둘 결론이 바뀌지
     * 않는 질문을 더 하지 않는다({@code ||} 가 아니라 이른 {@code return} 이고, 이 칸이 그걸 못 박는다).
     */
    @Test
    @DisplayName("토큰이 이미 폐기면 세션은 묻지 않는다 — 왕복 하나를 아낀다")
    void theSessionIsNotQueriedOnceTheTokenIsKnownRevoked() throws Exception {
        CountingChecker session = CountingChecker.active();
        TokenRevocationFilter filter =
                filter(CountingChecker.revoked(), session, new CountingLiveMetrics());
        authenticate();

        filter.doFilter(get(ENTER_URI), new MockHttpServletResponse(), new CountingChain());

        assertThat(session.calls.get()).isZero();
    }

    /**
     * 통과분은 왕복 <b>2회</b>다. 비용 서술이 javadoc 과 AGENTS.md 양쪽에 있으므로 수를 여기 박는다 —
     * 단축 평가가 아끼는 것은 <b>이미 폐기된</b> 경우뿐이라는 사실이 그 서술의 핵심이다.
     */
    @Test
    @DisplayName("폐기되지 않은 토큰은 통과하고, 그 비용이 왕복 2회다")
    void anActiveTokenKeepsItsAuthenticationAndCostsTwoLookups() throws Exception {
        CountingChecker token = CountingChecker.active();
        CountingChecker session = CountingChecker.active();
        CountingLiveMetrics metrics = new CountingLiveMetrics();
        TokenRevocationFilter filter = filter(token, session, metrics);
        authenticate();

        filter.doFilter(get(ENTER_URI), new MockHttpServletResponse(), new CountingChain());

        assertThat(authenticated()).isTrue();
        assertThat(token.calls.get() + session.calls.get()).isEqualTo(2);
        assertThat(metrics.revocationHits).isEmpty();
    }

    /**
     * ⭐ <b>게스트는 조회하지 않는다.</b> 시청·게스트 입장은 principal 이 없다. 그 경로까지
     * 조회하면 공개 트래픽 전부가 왕복을 치고, 저장소 장애가 공개 조회까지 끌고 내려간다 —
     * 아래 503 칸의 "대가" 가 미인증을 건드리지 않는다는 근거가 바로 이 칸이다.
     */
    @Test
    @DisplayName("⭐ 미인증 요청은 폐기 조회를 치지 않는다 — 공개 트래픽이 저장소에 매달리지 않는다")
    void anUnauthenticatedRequestNeverQueriesTheStore() throws Exception {
        CountingChecker token = CountingChecker.broken();
        CountingChecker session = CountingChecker.broken();
        CountingLiveMetrics metrics = new CountingLiveMetrics();
        TokenRevocationFilter filter = filter(token, session, metrics);
        CountingChain chain = new CountingChain();

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(get(ENTER_URI), response, chain);

        assertThat(token.calls.get() + session.calls.get()).isZero();
        assertThat(chain.passed.get()).isEqualTo(1);
        assertThat(response.getStatus())
                .as("체커가 깨져 있어도 미인증은 그 분기에 들어오지 않는다")
                .isEqualTo(200);
        assertThat(metrics.revocationLookupFailures).isZero();
    }

    /**
     * ⭐ <b>조회 실패는 폐기도 아니고 폐기 아님도 아니다 — 끊는다.</b>
     *
     * <p>어느 쪽으로 읽어도 틀린다. 통과시키면 저장소가 흔들리는 동안 폐기 갭이 그대로 돌아오고,
     * <b>거둬 버리면 공개 입장 경로에서 조용히 게스트로 내려간다</b>(그 경로가
     * {@code permitAll} 이라 거둬진 인증이 거부가 아니라 신원 부재로 읽힌다 —
     * {@code RevocationOnPublicRouteTest} 가 그 경로에서 200 + GUEST 를 고정한다).
     * 후자가 특히 나쁜데, 방주인이 자기 라이브에서 {@code owner} 를 잃으면서 응답이 200 이고
     * {@code error.code} 도 없어 클라이언트가 재시도할 신호조차 없다.
     *
     * <p>그래서 {@code 503 + Retry-After} 로 끊는다. 대가는 <b>토큰을 든</b> 요청이 장애 동안
     * 막히는 것이고, 미인증은 위 칸이 고정한 대로 조회에 들어오지 않아 계속 뜬다.
     */
    @Test
    @DisplayName("⭐ 조회가 실패하면 503 으로 끊는다 — 조용한 게스트 강등 경로를 코드에서 없앤다")
    void aFailedLookupStopsTheRequestLoudly() throws Exception {
        CountingLiveMetrics metrics = new CountingLiveMetrics();
        TokenRevocationFilter filter =
                filter(CountingChecker.broken(), CountingChecker.active(), metrics);
        CountingChain chain = new CountingChain();
        authenticate();

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(get(ENTER_URI), response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER))
                .as("원인이 인프라라 재시도가 몰리면 장애를 늘린다 — 형제의 1~3초보다 길다")
                .isEqualTo("5");
        assertThat(chain.passed.get())
                .as("체인을 타면 permitAll 경로에서 200 + GUEST 가 나간다")
                .isZero();
        assertThat(metrics.revocationLookupFailures)
                .as("규모는 로그가 아니라 이 수에서 본다 — 로그는 10초에 한 줄로 솎인다")
                .isEqualTo(1);
    }

    /**
     * ⭐ <b>실패 로그는 솎인다.</b> 이 경로의 볼륨은 공격자가 아니라 <b>장애</b> 손에 있다 —
     * 요청마다 WARN 을 남기면 로그 I/O 가 포화되며 그 사이 다른 WARN·ERROR 이 묻힌다(형제
     * {@code EnterRateLimiter} 가 거부마다의 WARN 을 피한 것과 같은 이유). 그리고 한 줄만 봐도
     * 규모가 보이게 누적을 같이 싣는다.
     */
    @Test
    @DisplayName("⭐ 실패 로그는 창당 한 줄로 솎이고, 다음 창의 줄이 자란 누적을 싣는다")
    void failureLogsAreThrottledAndCarryTheRunningTotal() throws Exception {
        AtomicLong clock = new AtomicLong();
        TokenRevocationFilter filter = new TokenRevocationFilter(
                CountingChecker.broken(), CountingChecker.active(),
                new CountingLiveMetrics(), clock::get);
        Logger logger = (Logger) LoggerFactory.getLogger(TokenRevocationFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            for (int i = 0; i < 25; i++) {
                authenticate();
                filter.doFilter(get(ENTER_URI), new MockHttpServletResponse(), new CountingChain());
            }

            assertThat(appender.list)
                    .as("25회 실패에 25줄이 나가면 로그 I/O 가 장애 규모에 비례한다")
                    .hasSize(1);
            assertThat(appender.list.getFirst().getFormattedMessage())
                    .as("첫 줄은 그 시점의 누적이다 — 찍을 때의 수이지 창의 합이 아니다")
                    .contains("누적=1건")
                    .contains("IllegalStateException");

            // 창을 넘긴다 — 두 번째 줄이 그동안 자란 누적을 실어야 "규모를 로그로 읽는다" 가 성립한다.
            clock.addAndGet(10_000_000_001L);
            authenticate();
            filter.doFilter(get(ENTER_URI), new MockHttpServletResponse(), new CountingChain());

            assertThat(appender.list).hasSize(2);
            assertThat(appender.list.get(1).getFormattedMessage())
                    .as("누적이 자라지 않으면 한 줄로 규모를 못 읽는다 — 억제의 값이 사라진다")
                    .contains("누적=26건");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    /**
     * ⭐ <b>상한에 거부된 입장은 폐기 조회를 치지 않는다 — 이 필터가 상한 뒤에 있는 이유 전부.</b>
     *
     * <p>폐기 조회를 JWT 필터에 넣으면 상한보다 앞이 된다(상한은 인증 여부로 버킷을 가르므로 JWT
     * 필터 뒤에 선다). 그러면 거부될 요청까지 왕복을 치고, 입장 상한은 CPU 천장이라 그 비용을
     * 막아 주지 못한다 — 공격 트래픽이 정확히 그 모양이다.
     *
     * <p>두 필터를 실제 순서대로 엮어 <b>조회 횟수</b>를 센다: 버스트 1 에 입장 2회면 통과 1·거부
     * 1 이고, 통과분만 조회하므로 토큰 축 조회가 <b>1회</b>여야 한다. 순서가 뒤집히면 2회가 된다.
     * (체인에 박힌 순서 자체는 {@code LiveFilterOrderTest} 가 설정에서 확인한다.)
     */
    @Test
    @DisplayName("⭐ 상한에 거부된 입장은 폐기 조회를 치지 않는다 — 순서가 비용이다")
    void anEntryRejectedByTheRateLimitNeverReachesTheStore() throws Exception {
        CountingChecker token = CountingChecker.active();
        TokenRevocationFilter revocation =
                filter(token, CountingChecker.active(), new CountingLiveMetrics());
        EnterRateLimitFilter rateLimit = new EnterRateLimitFilter(new EnterRateLimiter(
                new EnterRateLimitProperties(1, 1, 1, 1, 1), LiveMetrics.NOOP, () -> 0L));
        CountingChain tail = new CountingChain();
        authenticate();

        // 실제 체인 순서: 상한 → 폐기 조회 → 그 뒤.
        FilterChain afterRateLimit = (req, res) -> revocation.doFilter(req, res, tail);
        MockHttpServletResponse allowed = new MockHttpServletResponse();
        rateLimit.doFilter(get(ENTER_URI), allowed, afterRateLimit);
        MockHttpServletResponse rejected = new MockHttpServletResponse();
        rateLimit.doFilter(get(ENTER_URI), rejected, afterRateLimit);

        assertThat(allowed.getStatus()).isEqualTo(200);
        assertThat(rejected.getStatus()).as("전제 — 두 번째 입장은 상한에 걸려야 한다").isEqualTo(429);
        assertThat(token.calls.get())
                .as("거부분이 조회를 치면 상한이 왕복 비용을 막지 못한다")
                .isEqualTo(1);
    }
}
