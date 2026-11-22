package com.sapari.liveapp.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.sapari.liveapp.config.CredentialIssueRateLimitConfig;

/**
 * 판매자 자격증명 발급(썸네일 업로드 허가 · 송출 키 재발급) 상한.
 *
 * <p>고정하는 것: 한도를 넘기면 <b>429 와 {@code Retry-After}</b> 가 나가고 <b>체인을 타지 않는지</b>,
 * <b>신원 없는 요청은 세지 않고 넘기는지</b>, 다른 경로·다른 메서드는 건드리지 않는지,
 * <b>판매자 한 명이 다른 샤드의 판매자를 막지 않는지</b>, 두 경로가 서로의 버킷을 쓰지 않는지,
 * <b>최초 발급은 상한 밖인지</b>, 그리고 <b>미설정은 기본값 · 잘못된 값은 부팅 차단</b>인지.
 */
class CredentialIssueRateLimitFilterTest {

    private static final String THUMBNAIL_URI = "/api/v1/lives/thumbnails/uploads";
    private static final String REISSUE_URI =
            "/api/v1/lives/rooms/" + UUID.randomUUID() + "/ingress/reissue";

    /** 넉넉한 한도 — 검사하지 않는 쪽 리미터에 쓴다. */
    private static final CredentialIssueLimits WIDE = new CredentialIssueLimits(1_000, 1_000, 1);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** 멈춘 시계 — 가장 느린 버킷도 초당 1개는 차서 실시계면 "비었다" 단정의 창이 1초다. */
    private static CredentialIssueRateLimiter limiter(CredentialIssueLimits limits) {
        return new CredentialIssueRateLimiter("썸네일 업로드 허가", limits, () -> 0L);
    }

    private static CredentialIssueRateLimitFilter thumbnailFilter(CredentialIssueLimits limits) {
        return new CredentialIssueRateLimitFilter(limiter(limits), limiter(WIDE));
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

    private static void signIn(String subject) {
        LiveUserPrincipal principal = new LiveUserPrincipal(
                UUID.fromString(subject), UUID.randomUUID(), UUID.randomUUID(), "SELLER", "닉", "a@b.c");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private static int send(CredentialIssueRateLimitFilter filter, String uri, CountingChain chain)
            throws Exception {
        return send(filter, "POST", uri, chain);
    }

    private static int send(CredentialIssueRateLimitFilter filter, String method, String uri,
            CountingChain chain) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request(method, uri), response, chain);
        return response.getStatus();
    }

    @Test
    @DisplayName("⭐ 한도를 넘기면 429 와 Retry-After 가 나가고 체인을 타지 않는다")
    void overTheLimitIsRejectedWithoutTouchingTheChain() throws Exception {
        // given: 판매자 한도 1
        CredentialIssueRateLimitFilter filter = thumbnailFilter(new CredentialIssueLimits(1, 1, 1));
        CountingChain chain = new CountingChain();
        signIn(UUID.randomUUID().toString());

        // when & then: 첫 요청은 통과
        assertThat(send(filter, THUMBNAIL_URI, chain)).isEqualTo(HttpStatus.OK.value());

        // when: 두 번째는 거부
        MockHttpServletResponse second = new MockHttpServletResponse();
        filter.doFilter(request("POST", THUMBNAIL_URI), second, chain);

        // then
        assertThat(second.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(second.getHeader(HttpHeaders.RETRY_AFTER))
                .as("Retry-After 가 없으면 클라가 간격을 모르고 즉시 재시도한다").isEqualTo("1");
        assertThat(chain.passed.get())
                .as("거부된 요청이 체인으로 샜다 — 서명·LiveKit 호출 비용을 그대로 치른다").isEqualTo(1);
    }

    @Test
    @DisplayName("⭐ 신원이 없으면 세지 않고 그대로 넘긴다 — 인가가 401 로 끝낸다")
    void anonymousRequestsAreNotCounted() throws Exception {
        // given: 한도 1. 인증하지 않는다.
        CredentialIssueRateLimiter thumbnail = limiter(new CredentialIssueLimits(1, 1, 1));
        CredentialIssueRateLimitFilter filter =
                new CredentialIssueRateLimitFilter(thumbnail, limiter(WIDE));
        CountingChain chain = new CountingChain();

        // when: 익명으로 세 번
        for (int i = 0; i < 3; i++) {
            assertThat(send(filter, THUMBNAIL_URI, chain)).isEqualTo(HttpStatus.OK.value());
        }

        // then: 버킷이 그대로라 판매자가 여전히 한 번 쓸 수 있다
        assertThat(thumbnail.rejectedCount())
                .as("익명 요청을 셌다 — 막을 비용이 없는 요청이 판매자 몫을 먹는다").isZero();
        signIn(UUID.randomUUID().toString());
        assertThat(send(filter, THUMBNAIL_URI, chain)).isEqualTo(HttpStatus.OK.value());
        assertThat(chain.passed.get()).isEqualTo(4);
    }

    @Test
    @DisplayName("⭐ POST 만 센다 — GET 은 다른 핸들러다")
    void onlyPostCountsAgainstTheLimit() throws Exception {
        // given: 한도 1 을 POST 하나로 소진한다
        CredentialIssueRateLimitFilter filter = thumbnailFilter(new CredentialIssueLimits(1, 1, 1));
        CountingChain chain = new CountingChain();
        signIn(UUID.randomUUID().toString());
        send(filter, THUMBNAIL_URI, chain);

        // when & then: 같은 경로의 GET·HEAD 는 지난다
        assertThat(send(filter, "GET", THUMBNAIL_URI, chain)).isEqualTo(HttpStatus.OK.value());
        assertThat(send(filter, "HEAD", THUMBNAIL_URI, chain)).isEqualTo(HttpStatus.OK.value());
        assertThat(chain.passed.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("⭐ 최초 발급은 상한 밖이다 — 방당 한 번으로 묶인다")
    void firstIngressIssueIsOutsideTheLimit() throws Exception {
        // given: 재발급 한도 1 을 소진한다
        CredentialIssueRateLimiter reissue = limiter(new CredentialIssueLimits(1, 1, 1));
        CredentialIssueRateLimitFilter filter =
                new CredentialIssueRateLimitFilter(limiter(WIDE), reissue);
        CountingChain chain = new CountingChain();
        signIn(UUID.randomUUID().toString());
        send(filter, REISSUE_URI, chain);
        assertThat(send(filter, REISSUE_URI, chain)).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

        // when & then: 최초 발급(접미 없음)은 지난다
        String firstIssue = "/api/v1/lives/rooms/" + UUID.randomUUID() + "/ingress";
        assertThat(send(filter, firstIssue, chain)).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    @DisplayName("발급 경로가 아니면 건드리지 않는다")
    void otherPathsAreUntouched() throws Exception {
        // given: 둘 다 한도 1 을 이미 소진했다
        CredentialIssueLimits one = new CredentialIssueLimits(1, 1, 1);
        CredentialIssueRateLimitFilter filter =
                new CredentialIssueRateLimitFilter(limiter(one), limiter(one));
        CountingChain chain = new CountingChain();
        signIn(UUID.randomUUID().toString());
        send(filter, THUMBNAIL_URI, chain);
        send(filter, REISSUE_URI, chain);

        // when & then: 취소·수정·목록, 그리고 접두·접미만 같은 경로
        for (String uri : new String[]{
                "/api/v1/lives/rooms/" + UUID.randomUUID() + "/cancel",
                "/api/v1/lives/rooms",
                "/api/v1/lives/thumbnails/uploads/extra",
                "/api/v1/lives/rooms/" + UUID.randomUUID() + "/extra/ingress/reissue"}) {
            assertThat(send(filter, uri, chain)).as(uri).isEqualTo(HttpStatus.OK.value());
        }
        assertThat(chain.passed.get()).isEqualTo(6);
    }

    @Test
    @DisplayName("⭐ 판매자 한 명이 자기 샤드를 비워도 다른 샤드의 판매자는 지난다")
    void oneSellerDoesNotBlockSellersInOtherShards() throws Exception {
        // given: 샤드 100개, 샤드 몫 지속 1 · 버스트 2(= ⌈100/100⌉ × 헤드룸 2). 전역 버스트는 100 이다.
        CredentialIssueRateLimiter thumbnail = limiter(new CredentialIssueLimits(100, 100, 100));
        CredentialIssueRateLimitFilter filter =
                new CredentialIssueRateLimitFilter(thumbnail, limiter(WIDE));
        CountingChain chain = new CountingChain();
        String attacker = UUID.randomUUID().toString();
        String other = subjectInAnotherShard(thumbnail, attacker);

        // when: 공격자가 자기 샤드를 비운다
        signIn(attacker);
        send(filter, THUMBNAIL_URI, chain);
        send(filter, THUMBNAIL_URI, chain);
        assertThat(send(filter, THUMBNAIL_URI, chain)).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

        // then: 다른 샤드의 판매자는 지난다
        signIn(other);
        assertThat(send(filter, THUMBNAIL_URI, chain))
                .as("판매자 한 명이 판매자 전체를 굶겼다 — 샤드가 묶지 못했다")
                .isEqualTo(HttpStatus.OK.value());
    }

    @Test
    @DisplayName("⭐ 샤드가 남아도 전역 한도가 집계 천장이다")
    void theGlobalBucketIsTheCeiling() throws Exception {
        // given: 전역 지속 2 · 버스트 2, 샤드 2개(샤드 몫 버스트 2)
        CredentialIssueRateLimiter thumbnail = limiter(new CredentialIssueLimits(2, 2, 2));
        CredentialIssueRateLimitFilter filter =
                new CredentialIssueRateLimitFilter(thumbnail, limiter(WIDE));
        CountingChain chain = new CountingChain();
        String first = UUID.randomUUID().toString();
        String second = subjectInAnotherShard(thumbnail, first);

        // when: 두 샤드에서 한 건씩 + 한 건 더 — 샤드는 남지만 전역 2 를 넘는다
        signIn(first);
        send(filter, THUMBNAIL_URI, chain);
        signIn(second);
        send(filter, THUMBNAIL_URI, chain);

        // then
        assertThat(send(filter, THUMBNAIL_URI, chain)).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(thumbnail.rejectedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("⭐ 썸네일과 재발급은 서로의 버킷을 쓰지 않는다")
    void thumbnailAndReissueDoNotShareBuckets() throws Exception {
        // given: 둘 다 한도 1
        CredentialIssueLimits one = new CredentialIssueLimits(1, 1, 1);
        CredentialIssueRateLimiter reissue = limiter(one);
        CredentialIssueRateLimitFilter filter =
                new CredentialIssueRateLimitFilter(limiter(one), reissue);
        CountingChain chain = new CountingChain();
        signIn(UUID.randomUUID().toString());

        // when: 재발급 버킷을 비운다
        send(filter, REISSUE_URI, chain);
        assertThat(send(filter, REISSUE_URI, chain)).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

        // then: 썸네일은 지나고, 그 뒤 썸네일을 비워도 재발급 거부 수는 그대로다
        assertThat(send(filter, THUMBNAIL_URI, chain)).isEqualTo(HttpStatus.OK.value());
        assertThat(send(filter, THUMBNAIL_URI, chain)).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(reissue.rejectedCount()).isEqualTo(1);
        assertThat(chain.passed.get()).isEqualTo(2);
    }

    /**
     * 거부 로그가 솎이는지, 그리고 <b>누적 수가 언제 보이는지</b>. 첫 거부는 즉시 로그되므로 그 줄은
     * 그 시점의 수(1)를 싣고, 규모는 다음 창의 줄에서 보인다 — 시계를 움직여 두 창을 본다.
     */
    @Test
    @DisplayName("⭐ 거부 로그는 솎이고, 누적 수는 다음 창의 줄에서 보인다")
    void rejectionLogIsThrottledAndScaleAppearsInTheNextWindow() throws Exception {
        // given: 움직일 수 있는 시계
        long[] now = {0L};
        CredentialIssueRateLimitFilter filter = new CredentialIssueRateLimitFilter(
                new CredentialIssueRateLimiter("썸네일 업로드 허가",
                        new CredentialIssueLimits(1, 1, 1), () -> now[0]),
                limiter(WIDE));
        CountingChain chain = new CountingChain();
        signIn(UUID.randomUUID().toString());
        Logger logger = (Logger) LoggerFactory.getLogger(CredentialIssueRateLimiter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            // when: 같은 창에서 20회 — 1회 통과, 19회 거부
            for (int i = 0; i < 20; i++) {
                send(filter, THUMBNAIL_URI, chain);
            }

            // then
            assertThat(appender.list)
                    .as("거부마다 한 줄이면 로그 I/O 가 폭주 규모에 비례한다").hasSize(1);
            assertThat(appender.list.getFirst().getFormattedMessage())
                    .contains("썸네일 업로드 허가 상한")
                    .as("첫 줄은 최종 누적이 아니라 그 시점의 수다").contains("누적 1건");

            // when: 창을 넘긴다(10초). 버스트가 1 이라 두 번째 요청부터 다시 거부다.
            now[0] += java.time.Duration.ofSeconds(11).toNanos();
            send(filter, THUMBNAIL_URI, chain);
            send(filter, THUMBNAIL_URI, chain);

            // then
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
                new ApplicationContextRunner().withUserConfiguration(CredentialIssueRateLimitConfig.class);

        // 미설정 → 기본값으로 뜬다
        runner.run(context -> assertThat(context).hasNotFailed());

        for (String prefix : new String[]{"live.thumbnail.rate-limit", "live.ingress.reissue.rate-limit"}) {
            // 0 → 막힌다. 조용히 기본값으로 떨어지면 "설정했다고 믿는데 안 걸린 파드" 가 된다.
            runner.withPropertyValues(prefix + ".seller-permits-per-second=0")
                    .run(context -> assertThat(context).as(prefix).hasFailed());
            // burst < permits → 막힌다.
            runner.withPropertyValues(prefix + ".seller-permits-per-second=20", prefix + ".seller-burst=5")
                    .run(context -> assertThat(context).as(prefix).hasFailed());
            // 샤드 > permits → 막힌다(샤드당 허용량이 0 이 된다).
            runner.withPropertyValues(prefix + ".seller-permits-per-second=10",
                            prefix + ".seller-burst=10", prefix + ".seller-shards=11")
                    .run(context -> assertThat(context).as(prefix).hasFailed());
            // 샤드 0 → 막힌다.
            runner.withPropertyValues(prefix + ".seller-shards=0")
                    .run(context -> assertThat(context).as(prefix).hasFailed());
        }
    }

    /** {@code subject} 와 다른 샤드에 떨어지는 주체를 찾는다(키가 랜덤이라 미리 정할 수 없다). */
    private static String subjectInAnotherShard(CredentialIssueRateLimiter limiter, String subject) {
        int shard = limiter.shardIndexFor(subject);
        for (int i = 0; i < 1_000; i++) {
            String candidate = UUID.randomUUID().toString();
            if (limiter.shardIndexFor(candidate) != shard) {
                return candidate;
            }
        }
        throw new AssertionError("다른 샤드의 주체를 찾지 못했다");
    }
}
