package com.sapari.liveapp.security;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.sapari.common.securityjwt.store.AccessTokenRevocationChecker;
import com.sapari.common.securityjwt.store.SessionRevocationChecker;
import com.sapari.live.application.port.LiveMetrics;
import com.sapari.live.application.port.TokenRevocationAxis;

/**
 * 폐기된 access 토큰의 인증을 거둬 가는 자리.
 *
 * <p><b>왜 필요한가.</b> {@link StatelessJwtAuthenticationFilter} 는 서명·만료·claims 만 보므로,
 * 로그아웃·차단된 토큰이 자기 {@code exp} 까지 인증을 통과했다. 그 사이 입장은 <b>룸 토큰을
 * 새로 발급</b>하고({@code EnterLiveService}) 그 토큰 한 장이 chat 의 유일한 인증 수단이다
 * ({@code RoomTokenVerifier}). 변경 경로도 같이 열려 있었다: 강퇴·밴 해제·사전 관리는
 * {@code LiveSecurityConfig} 에서 SELLER·ADMIN 역할로 갈리고, 그 역할의 출처가 이 claims 다.
 *
 * <p><b>왜 JWT 필터 안이 아니라 별 필터인가 — 순서가 비용이다.</b> 입장 상한
 * ({@link EnterRateLimitFilter}) 은 인증 여부로 버킷을 가르므로 JWT 필터 <b>뒤</b>에 선다. 폐기
 * 조회를 JWT 필터에 넣으면 상한에 걸려 <b>거부될 요청까지</b> 저장소를 한 번씩 치고 간다 —
 * 그 상한은 CPU 천장이라 왕복 비용을 막아 주지 못한다. 상한 뒤로 내리면 거부분은 왕복이 0 이고,
 * 그런데도 {@code AuthorizationFilter} 보다는 앞이라 <b>인가 판정 전에</b> 컨텍스트가 비워진다.
 * 입장 밖 경로는 상한 필터가 {@code EnterPaths.isEnterRequest} 로 즉시 통과시키므로 이 순서가
 * 그쪽 비용을 늘리지 않는다.
 *
 * <p><b>비용(통과분).</b> 폐기되지 않은 토큰은 요청마다 왕복 <b>2회</b>다({@code jti} 다음
 * {@code sid}). 단축 평가는 <b>이미 폐기된</b> 경우만 1회로 줄인다. 상한이 아끼는 것은 거부분의
 * 왕복이고 통과분의 2회는 그대로다 — 한 번으로 줄이려면 두 키를 한 명령으로 묻는 수밖에 없고,
 * 그건 {@code common/auth} 의 저장소 둘(api-app 과 공유)을 고치는 일이라 이 티켓 밖이다.
 *
 * <p><b>두 축은 다른 사건이다.</b> {@code jti}(토큰 한 장)와 {@code sid}(로그인 세션 전부)는
 * 대응이 다르므로 지표에서 가른다({@link TokenRevocationAxis}). 둘 다
 * {@code JwtTokenProvider} 가 <b>필수로 발급하고 필수로 검증</b>하므로
 * ({@code getRequiredTokenId}/{@code getRequiredSessionId} 는 없으면 던진다) 이 필터가 볼 값이
 * 비어 있는 access 토큰은 애초에 인증을 통과하지 못한다.
 *
 * <h2>"폐기 확정" 과 "조회 불가" 를 가른다</h2>
 *
 * <p><b>폐기 확정</b>이면 컨텍스트만 거두고 체인은 계속 간다. 역할 요구 경로는 401 이 되고
 * ({@code JwtAuthenticationEntryPoint} 가 봉투를 만든다) <b>공개 경로는 게스트로 내려간다</b> —
 * 그게 맞다. 폐기된 사용자는 헤더를 떼면 어차피 낯선 사람과 같은 것을 받으므로 권한 획득이 0 이다.
 *
 * <p><b>조회 불가</b>(저장소 장애)는 같은 처리를 하면 안 된다. 그렇게 두면 공개 입장 경로가
 * {@code permitAll} 이라 <b>200 + GUEST 룸 토큰</b>이 나간다. 그 경로의 성질은
 * {@code RevocationOnPublicRouteTest} 의 확정-폐기 칸이 고정한다(거둬진 컨텍스트 → 200 + GUEST);
 * 조회 실패를 "거두기" 로 처리하면 같은 길을 타는데, 역변이로 확인했다. 그러면 장애 중에
 * 방주인이 자기 라이브에 들어가 {@code role=GUEST · owner=false} 를 받고, 채팅 발언권과 특권 뷰를
 * 잃으면서 응답은 200 이고 {@code error.code} 도 없다. 401 보다 나쁜 모양이다(401 은 적어도
 * 보인다). 그래서 이쪽은 <b>{@code 503} 으로 끊는다</b> — 조용한 강등 경로를 코드에서 없앤다.
 *
 * <p>그 선택의 대가는 <b>토큰을 든 요청</b>이 저장소 장애 동안 막히는 것이다. 미인증 요청은 이
 * 분기에 들어오지 않으므로 <b>공개 조회와 게스트 입장은 계속 뜬다</b>. 즉 로그아웃 상태의
 * 비회원 시청은 장애에 영향을 받지 않는다.
 *
 * <p>{@code Retry-After} 는 고정 5초다. 더 짧게 주면 흔들리는 저장소에 재시도가 몰려 장애를
 * 늘린다 — 이 응답의 원인이 호출자가 아니라 인프라이기 때문에, 형제 상한의 1~3초 지터와 다른
 * 값을 쓴다.
 *
 * <p>본문을 싣지 않는 것은 형제 {@link EnterRateLimitFilter} 와 같다 — 필터는 응답 봉투 규약
 * 밖이고, 던지면 공용 핸들러가 요청마다 WARN 을 남긴다. 그래서 이 {@code 503} 에도
 * {@code error.code} 가 없다.
 */
@Slf4j
public class TokenRevocationFilter extends OncePerRequestFilter {

    private static final long LOG_INTERVAL_NANOS = 10_000_000_000L; // 10s
    private static final long RETRY_AFTER_SECONDS = 5L;

    private final AccessTokenRevocationChecker accessTokenRevocationChecker;
    private final SessionRevocationChecker sessionRevocationChecker;
    private final LiveMetrics metrics;
    private final LongSupplier nanoClock;

    /** 프로세스 수명 누적. 한 줄만 봐도 규모가 보이게 로그에 같이 싣는다. */
    private final AtomicLong lookupFailures = new AtomicLong();
    private final AtomicLong lastFailureLogNanos;

    public TokenRevocationFilter(
            AccessTokenRevocationChecker accessTokenRevocationChecker,
            SessionRevocationChecker sessionRevocationChecker,
            LiveMetrics metrics
    ) {
        this(accessTokenRevocationChecker, sessionRevocationChecker, metrics, System::nanoTime);
    }

    /**
     * 시계를 주입받는 생성자 — <b>테스트 전용이다.</b> 형제 {@code EnterRateLimiter} 와 같은 이음이고,
     * 같은 이유다: 로그 억제 간격이 실시계면 "한 창에 한 줄" 단정이 CI 부하에서 플레이크가 된다.
     */
    TokenRevocationFilter(
            AccessTokenRevocationChecker accessTokenRevocationChecker,
            SessionRevocationChecker sessionRevocationChecker,
            LiveMetrics metrics,
            LongSupplier nanoClock
    ) {
        this.accessTokenRevocationChecker = accessTokenRevocationChecker;
        this.sessionRevocationChecker = sessionRevocationChecker;
        this.metrics = metrics;
        this.nanoClock = nanoClock;
        // 첫 실패는 반드시 찍힌다 — 0 으로 두면 멈춘 시계에서 첫 줄이 사라진다.
        this.lastFailureLogNanos = new AtomicLong(nanoClock.getAsLong() - LOG_INTERVAL_NANOS);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        LiveUserPrincipal principal = authenticatedPrincipal();
        if (principal == null) {
            filterChain.doFilter(request, response);
            return;
        }

        TokenRevocationAxis hit;
        try {
            hit = revokedAxis(principal);
        } catch (RuntimeException e) {
            // 조회 불가는 "폐기 아님" 도 아니고 "폐기" 도 아니다. 어느 쪽으로 읽어도 틀린다 —
            // 통과시키면 폐기 갭이 그대로 돌아오고, 거둬 버리면 공개 입장 경로에서 조용히
            // 게스트로 내려가 방주인이 자기 방 권한을 잃는다(공개 경로에서 거둬진 컨텍스트가
            // 200 + GUEST 로 읽히는 것은 RevocationOnPublicRouteTest 가 고정한다). 끊는다.
            metrics.tokenRevocationLookupFailed();
            logFailureThrottled(request, e);
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(RETRY_AFTER_SECONDS));
            return;
        }

        if (hit != null) {
            metrics.tokenRevocationHit(hit);
            SecurityContextHolder.clearContext();
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 인증 요청이면 그 신원, 아니면 {@code null}.
     *
     * <p>판정 기준을 {@code EnterRateLimitFilter.member()} 와 같게 둔다 — principal 이
     * {@code LiveUserPrincipal} 인가. {@code isAuthenticated()} 는 익명 인증에도 참이라 기준이
     * 되지 못하고, 두 필터의 기준이 갈리면 상한을 회원으로 센 요청을 이쪽이 안 볼 수 있다.
     */
    private static LiveUserPrincipal authenticatedPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof LiveUserPrincipal principal) {
            return principal;
        }
        return null;
    }

    /**
     * 적중한 축, 아니면 {@code null}. 토큰 축이 참이면 세션 축을 묻지 않는다 — 왕복 하나를 아낀다.
     *
     * <p><b>정상 경로는 왕복 2회다</b>(둘 다 거짓이어야 통과이므로 조기 반환이 아끼는 것은 이미
     * 폐기된 토큰의 경우뿐이다). 그 둘을 하나로 묶을 수 있는지 재 봤고, <b>묶지 않기로 했다.</b>
     *
     * <p>실측(Testcontainers {@code redis:7.4}, 로컬 Docker 루프백, n=20,000):
     * <pre>
     *   EXISTS 1키        p50 125.6µs  p99 164.2µs  p999 253.1µs
     *   EXISTS 2키 순차   p50 244.6µs  p99 305.2µs  p999 555.2µs   ← 현행
     *   EXISTS 다중키 1회 p50 124.6µs  p99 160.7µs  p999 255.8µs
     * </pre>
     * 묶으면 요청당 약 120µs(p50)·145µs(p99)를 아낀다. 왕복 수에 정확히 비례하고 키 두 개를
     * 한 명령에 실어도 한 개와 같다.
     *
     * <p><b>그런데 이 경로의 용량 벽이 여기가 아니다.</b> {@code EnterRateLimitProperties} 가
     * 같은 요청의 RS256 룸 토큰 서명을 685µs(코어당 1,458건/s)로 실측해 두었고, 두 버킷 합
     * 1,000/s 가 그 천장의 69% 라고 적는다. 그리고 이 필터는 <b>컨트롤러 앞</b>이라 DB 커넥션을
     * 쥐지 않으므로, 같은 javadoc 이 세는 또 하나의 분모(Hikari 풀 10 · 커넥션당 10ms 예산)에
     * 들어가지 않는다. MVC 스레드 점유로 환산하면 1,000/s 에서 왕복 하나는 0.125 스레드
     * (Tomcat 기본 200)다. 즉 1,000/s 에서 이 왕복은 어느 분모에도 닿지 않는다.
     *
     * <p><b>묶는 값은 작고 대가는 설계에 남는다.</b> {@code EXISTS k1 k2} 는 <i>개수</i>를
     * 돌려주므로 어느 축인지를 잃고({@code live.token.revoked{axis}} 가 그 태그를 쓴다) 적중 시
     * 축을 다시 묻는 분기가 생긴다. 그리고 두 조회는 {@code common/auth} 의 서로 다른 저장소
     * 둘이고 api-app 이 각각 따로 쓰므로, 묶으려면 그 공용 모듈에 합친 포트를 새로 내야 한다.
     * 685µs 벽 앞에서 120µs 를 줄이려고 공용 모듈의 포트를 늘리지 않는다.
     *
     * <p>⚠️ <b>판단이 뒤집히는 조건</b>: 위 수는 로컬 Docker 루프백이라 운영 왕복이 아니다.
     * 전달되는 것은 <b>비율</b>(왕복 2회 = 2배, 다중키 = 1배)이다. 왕복 하나가 서명 한 번 값
     * (~0.7ms)에 닿으면 이 단계가 경로의 지배 비용이 되므로 그때 다시 본다.
     *
     * <p>⚠️ 파이프라인({@code executePipelined})은 이 모양에 쓸 수 없다 — <b>호출마다 전용
     * 연결을 연다</b>(실측: 20,000회에서 {@code BindException: Can't assign requested address}
     * 로 임시 포트가 고갈됐다). 묶는다면 다중키 {@code EXISTS} 쪽이다.
     */
    private TokenRevocationAxis revokedAxis(LiveUserPrincipal principal) {
        if (accessTokenRevocationChecker.isRevoked(principal.tokenId())) {
            return TokenRevocationAxis.JTI;
        }
        if (sessionRevocationChecker.isRevoked(principal.sessionId())) {
            return TokenRevocationAxis.SID;
        }
        return null;
    }

    /**
     * 실패 로그는 10초에 한 줄로 솎는다 — 이 경로의 볼륨은 공격자가 아니라 <b>장애</b> 손에 있고,
     * 요청마다 WARN 을 남기면 CPU 대신 로그 I/O 가 포화되며 그 사이 다른 WARN·ERROR 이 묻힌다
     * (형제 {@code EnterRateLimitFilter} 가 거부마다의 WARN 을 피한 것과 같은 이유). 규모는
     * {@code live.token.revocation.failed} 에서 본다.
     */
    private void logFailureThrottled(HttpServletRequest request, RuntimeException cause) {
        long count = lookupFailures.incrementAndGet();
        long now = nanoClock.getAsLong();
        long last = lastFailureLogNanos.get();
        if (now - last < LOG_INTERVAL_NANOS || !lastFailureLogNanos.compareAndSet(last, now)) {
            return;
        }
        log.warn("폐기 조회 실패 — 토큰을 든 요청을 503 으로 끊는다(미인증 요청은 영향 없음). "
                        + "method={}, uri={}, 프로세스 누적={}건, reason={}",
                request.getMethod(), request.getRequestURI(), count,
                cause.getClass().getSimpleName());
    }
}
