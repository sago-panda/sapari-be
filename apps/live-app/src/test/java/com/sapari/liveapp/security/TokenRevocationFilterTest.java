package com.sapari.liveapp.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.sapari.common.securityjwt.store.AccessTokenRevocationChecker;
import com.sapari.common.securityjwt.store.SessionRevocationChecker;
import com.sapari.live.application.port.LiveMetrics;

/**
 * 폐기 조회 — 필터.
 *
 * <p>고정하는 것: <b>폐기된 토큰의 인증이 거둬지는지</b>(안 거둬지면 로그아웃·강등된 토큰이
 * 자기 {@code exp} 까지 룸 토큰을 새로 받아 간다), <b>두 축이 각자 거두는지</b>({@code jti} 는
 * 그 토큰만, {@code sid} 는 그 세션 전부), <b>조회 실패가 fail-closed 인지</b>, <b>게스트는
 * 조회하지 않는지</b>, 그리고 <b>상한에 거부된 입장이 조회를 치지 않는지</b> — 마지막 칸이
 * 이 필터를 JWT 필터가 아니라 상한 뒤에 둔 이유 전부다.
 */
class TokenRevocationFilterTest {

    private static final String ENTER_URI = "/api/v1/lives/rooms/" + UUID.randomUUID();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** 호출 수를 세는 가짜 — "몇 번 Redis 를 쳤나" 가 이 파일의 두 번째 축이다. */
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

    private static UUID authenticate() {
        UUID tokenId = UUID.randomUUID();
        LiveUserPrincipal principal = new LiveUserPrincipal(
                UUID.randomUUID(), tokenId, UUID.randomUUID(), "SELLER", "닉", "a@b.c");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        return tokenId;
    }

    private static boolean authenticated() {
        return SecurityContextHolder.getContext().getAuthentication() != null;
    }

    /**
     * ⭐ <b>폐기된 토큰의 인증이 거둬진다.</b>
     *
     * <p>이 칸이 깨지면 로그아웃·차단·역할 강등된 토큰이 자기 {@code exp} 까지 인증을 통과하고,
     * 그 사이 입장이 <b>룸 토큰을 새로 발급</b>해 강등 전 역할이 담긴 채팅 입장권이 만들어진다.
     * 체인은 계속 탄다 — 401 본문은 {@code JwtAuthenticationEntryPoint} 가 만든다.
     */
    @Test
    @DisplayName("⭐ 폐기된 토큰(jti)의 인증을 거둔다 — 체인은 계속 탄다")
    void aRevokedTokenLosesItsAuthentication() throws Exception {
        TokenRevocationFilter filter =
                new TokenRevocationFilter(CountingChecker.revoked(), CountingChecker.active());
        CountingChain chain = new CountingChain();
        authenticate();

        filter.doFilter(get(ENTER_URI), new MockHttpServletResponse(), chain);

        assertThat(authenticated())
                .as("거두지 않으면 폐기된 토큰이 룸 토큰을 새로 받아 간다")
                .isFalse();
        assertThat(chain.passed.get())
                .as("응답은 체인 끝의 EntryPoint 가 봉투로 만든다 — 여기서 끊으면 본문이 빈다")
                .isEqualTo(1);
    }

    /**
     * ⭐ <b>두 축은 다른 사건이다.</b> {@code jti} 는 그 토큰 한 장, {@code sid} 는 그 로그인
     * 세션의 모든 토큰을 접는다. 세션 축만 폐기된 경우를 따로 재지 않으면 {@code sid} 조회가
     * 사실상 죽은 코드가 되고, "전 기기 로그아웃" 이 live-app 에만 안 먹는다.
     */
    @Test
    @DisplayName("⭐ 세션(sid)만 폐기돼도 인증을 거둔다 — 전 기기 로그아웃이 여기도 먹는다")
    void aRevokedSessionAlsoLosesItsAuthentication() throws Exception {
        TokenRevocationFilter filter =
                new TokenRevocationFilter(CountingChecker.active(), CountingChecker.revoked());
        authenticate();

        filter.doFilter(get(ENTER_URI), new MockHttpServletResponse(), new CountingChain());

        assertThat(authenticated()).isFalse();
    }

    /**
     * 토큰 축이 이미 폐기면 세션 축을 묻지 않는다 — 조회 하나가 왕복 하나다. 거둘 결론이 바뀌지
     * 않는 질문을 더 하지 않는다({@code ||} 의 단축 평가가 그것이고, 이 칸이 그걸 못 박는다).
     */
    @Test
    @DisplayName("토큰이 이미 폐기면 세션은 묻지 않는다 — 왕복 하나를 아낀다")
    void theSessionIsNotQueriedOnceTheTokenIsKnownRevoked() throws Exception {
        CountingChecker session = CountingChecker.active();
        TokenRevocationFilter filter =
                new TokenRevocationFilter(CountingChecker.revoked(), session);
        authenticate();

        filter.doFilter(get(ENTER_URI), new MockHttpServletResponse(), new CountingChain());

        assertThat(session.calls.get()).isZero();
    }

    @Test
    @DisplayName("폐기되지 않은 토큰은 그대로 통과한다")
    void anActiveTokenKeepsItsAuthentication() throws Exception {
        TokenRevocationFilter filter =
                new TokenRevocationFilter(CountingChecker.active(), CountingChecker.active());
        authenticate();

        filter.doFilter(get(ENTER_URI), new MockHttpServletResponse(), new CountingChain());

        assertThat(authenticated()).isTrue();
    }

    /**
     * ⭐ <b>게스트는 조회하지 않는다.</b> 시청·게스트 입장은 principal 이 없다. 그 경로까지
     * 조회하면 공개 트래픽 전부가 Redis 왕복을 치고, Redis 장애가 공개 조회까지 끌고 내려간다.
     */
    @Test
    @DisplayName("⭐ 미인증 요청은 폐기 조회를 치지 않는다 — 공개 트래픽이 Redis 에 매달리지 않는다")
    void anUnauthenticatedRequestNeverQueriesTheStore() throws Exception {
        CountingChecker token = CountingChecker.revoked();
        CountingChecker session = CountingChecker.revoked();
        TokenRevocationFilter filter = new TokenRevocationFilter(token, session);
        CountingChain chain = new CountingChain();

        filter.doFilter(get(ENTER_URI), new MockHttpServletResponse(), chain);

        assertThat(token.calls.get() + session.calls.get()).isZero();
        assertThat(chain.passed.get()).isEqualTo(1);
    }

    /**
     * ⭐ <b>조회 실패는 "폐기 아님" 이 아니다.</b> 통과시키면 Redis 가 흔들리는 동안 폐기 갭이
     * 그대로 돌아온다 — 그게 이 필터가 닫으려던 것이다. 공통 {@code JwtAuthenticationFilter} 의
     * 폐기 조회도 같은 방향이라 두 앱이 같은 답을 준다.
     *
     * <p>대가는 Redis 장애가 live-app 의 <b>인증된</b> 요청을 전부 막는다는 것이다. 위 칸이
     * 미인증은 조회조차 하지 않음을 고정하므로, 그 장애에도 공개 조회와 게스트 입장은 뜬다.
     */
    @Test
    @DisplayName("⭐ 조회가 실패하면 인증을 거둔다(fail-closed) — 장애가 폐기 갭을 되살리지 않는다")
    void aFailedLookupIsTreatedAsRevoked() throws Exception {
        TokenRevocationFilter filter =
                new TokenRevocationFilter(CountingChecker.broken(), CountingChecker.active());
        authenticate();

        filter.doFilter(get(ENTER_URI), new MockHttpServletResponse(), new CountingChain());

        assertThat(authenticated()).isFalse();
    }

    /**
     * ⭐ <b>상한에 거부된 입장은 폐기 조회를 치지 않는다 — 이 필터가 상한 뒤에 있는 이유 전부.</b>
     *
     * <p>폐기 조회를 JWT 필터에 넣으면 상한보다 앞이 된다(상한은 인증 여부로 버킷을 가르므로 JWT
     * 필터 뒤에 선다). 그러면 거부될 요청까지 Redis 왕복을 치고, 입장 상한은 CPU 천장이라 그
     * 비용을 막아 주지 못한다 — 공격 트래픽이 정확히 그 모양이다.
     *
     * <p>여기서는 두 필터를 실제 순서대로 엮어 <b>조회 횟수</b>를 센다: 버스트 1 에 입장 2회면
     * 통과 1·거부 1 이고, 조회는 <b>1회</b>여야 한다. 순서가 뒤집히면 2회가 된다.
     * (체인에 박힌 순서 자체는 {@code LiveFilterOrderTest} 가 설정에서 확인한다.)
     */
    @Test
    @DisplayName("⭐ 상한에 거부된 입장은 폐기 조회를 치지 않는다 — 순서가 비용이다")
    void anEntryRejectedByTheRateLimitNeverReachesTheStore() throws Exception {
        CountingChecker token = CountingChecker.active();
        TokenRevocationFilter revocation =
                new TokenRevocationFilter(token, CountingChecker.active());
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
