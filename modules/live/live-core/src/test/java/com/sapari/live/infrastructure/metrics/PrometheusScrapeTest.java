package com.sapari.live.infrastructure.metrics;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;

import com.sapari.live.application.port.RoomTokenSignatureSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

import com.sapari.live.application.port.LiveMetrics;
import com.sapari.live.application.port.ReconcileAbortReason;
import com.sapari.live.application.port.ReconcileJob;

/**
 * <b>실제 스크레이프 출력</b>으로 지표를 검증한다. 나머지 테스트가 쓰는 {@code SimpleMeterRegistry} 로는
 * 잡히지 않는 결함이 여기서만 드러나기 때문이다.
 *
 * <p>프로메테우스 레지스트리는 <b>같은 이름의 미터가 모두 같은 태그 키 집합을 갖도록 요구</b>한다.
 * 어기면 예외가 아니라 <b>WARN 한 줄 뒤 조용한 등록 실패</b>이고(그 뒤로는 debug 로 내려가 로그도 없다),
 * 결과적으로 나중에 등록되는 시계열이 통째로 사라진다. {@code SimpleMeterRegistry} 는 이 제약이 없어
 * 단위 테스트는 전부 통과한다 — 운영에서만, 그것도 조용히 실패한다.
 *
 * <p>실제로 이 저장소에서 그 일이 있었다: 완료/실패에는 {@code reason} 이 없고 중단에만 있어서
 * <b>중단 시계열이 스크레이프에 나오지 않았다.</b> 하필 그 지표는 "가드가 회차를 삼켰다는 유일한 외부
 * 신호" 다. 그래서 이 테스트가 존재한다 — 지표를 하나 추가할 때마다 여기에 한 줄씩 늘릴 것.
 */
class PrometheusScrapeTest {

    private PrometheusMeterRegistry registry;
    private LiveMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        metrics = new MicrometerLiveMetrics(registry);
    }

    @Test
    @DisplayName("회차의 세 결과가 모두 스크레이프에 나온다 — 태그 키 집합이 어긋나면 나중 것이 조용히 사라진다")
    void allThreeOutcomes_appearInScrape() {
        metrics.reconcileRoundCompleted(ReconcileJob.END_STALE_LIVE, Duration.ofSeconds(1));
        metrics.reconcileRoundAborted(
                ReconcileJob.END_STALE_LIVE, ReconcileAbortReason.NO_ACTIVE_EGRESS_CLUSTER_WIDE);
        metrics.reconcileRoundFailed(ReconcileJob.END_STALE_LIVE);

        String scrape = registry.scrape();

        assertThat(scrape).contains("outcome=\"completed\"");
        assertThat(scrape).contains("outcome=\"aborted\"");
        assertThat(scrape).contains("outcome=\"failed\"");
        // 중단 사유가 살아 있어야 한다 — 이게 오설정(200+빈 목록)을 가리키는 값이다
        assertThat(scrape).contains("reason=\"no_active_egress_cluster_wide\"");
    }

    @Test
    @DisplayName("등록 실패가 없었음을 미터 개수로 확인한다 — 조용히 버려지면 개수가 줄어든다")
    void noMeterIsSilentlyDropped() {
        metrics.reconcileRoundCompleted(ReconcileJob.EXPIRE_READY, Duration.ofSeconds(1));
        metrics.reconcileRoundAborted(
                ReconcileJob.EXPIRE_READY, ReconcileAbortReason.NO_ACTIVE_EGRESS_CLUSTER_WIDE);

        long rounds = registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().equals("live.reconcile.round"))
                .count();

        assertThat(rounds).isEqualTo(2);
    }

    /**
     * ⭐ <b>입장 거부 지표의 이름과 태그 <i>값</i>이 스크레이프 출력에 그대로 있다.</b>
     *
     * <p>이 칸이 없던 동안 {@code modules/live/AGENTS.md} 가 태그 값을 {@code population=GUEST}
     * 로 적었다 — 코드는 {@code name().toLowerCase()} 라 실제 값이 {@code "guest"} 다. 당직자가
     * 문서대로 쿼리하면 <b>0건이 아니라 시계열 없음</b>이 나오고, 그것은 "알림이 안 온 것" 과
     * 구분되지 않는다. 그 면(이름·태그 문자열)을 재는 칸이 저장소에 하나도 없었다 —
     * 다른 테스트들은 전부 포트 호출 여부만 센다.
     *
     * <p>그래서 단정을 스크레이프 <b>문자열</b>에 둔다. 상수를 참조하면 상수와 문서가 어긋나는
     * 바로 그 결함을 못 본다.
     */
    @Test
    @DisplayName("⭐ 입장 거부 지표의 이름·태그 값이 스크레이프 출력에 있다")
    void enterRejected_appearsInScrape() {
        metrics.enterRateLimited(com.sapari.live.application.port.EnterPopulation.GUEST,
                com.sapari.live.application.port.EnterRejectionGate.GLOBAL);
        metrics.enterRateLimited(com.sapari.live.application.port.EnterPopulation.MEMBER,
                com.sapari.live.application.port.EnterRejectionGate.SHARD);
        metrics.enterRateLimited(com.sapari.live.application.port.EnterPopulation.MEMBER,
                com.sapari.live.application.port.EnterRejectionGate.GLOBAL);

        String scrape = registry.scrape();

        assertThat(scrape)
                .as("이름은 점으로 구분한 소문자 명사구 → 프로메테우스에서 밑줄 + _total")
                .contains("live_enter_rejected_total");
        assertThat(scrape)
                .as("태그 값은 소문자다 — 문서가 대문자로 적으면 쿼리가 시계열을 못 찾는다")
                .contains("population=\"guest\"")
                .contains("population=\"member\"");
        assertThat(scrape)
                .as("대문자 값은 나오지 않는다 — 이 단정이 문서 쪽 드리프트를 잡는 면이다")
                .doesNotContain("population=\"GUEST\"")
                .doesNotContain("gate=\"SHARD\"");
        assertThat(scrape)
                .as("관문이 갈려 나와야 '그 계정을 찾아라' 와 '한도를 올려라' 를 구분한다")
                .contains("gate=\"shard\"")
                .contains("gate=\"global\"");
    }

    /**
     * ⭐ <b>폐기 조회의 두 지표가 스크레이프에 있고, 서로 다른 이름이다.</b>
     *
     * <p>적중과 조회 실패를 <b>같은 이름의 다른 태그</b>로 두면 안 되는 이유가 여기서 두 겹이다.
     * 하나는 대응이 반대라는 설계 이유({@code LiveMetrics} javadoc)이고, 하나는 이 파일의 머리글이
     * 적은 제약이다 — 같은 이름의 미터는 태그 키 집합이 같아야 하고, 적중에는 {@code axis} 가
     * 있고 실패에는 없으므로 한 이름에 넣으면 <b>나중에 등록되는 쪽이 조용히 사라진다.</b>
     * 중단 시계열이 실제로 그렇게 사라진 적이 있다.
     */
    @Test
    @DisplayName("⭐ 폐기 적중·조회 실패 지표의 이름·태그 값이 스크레이프 출력에 있다")
    void tokenRevocation_appearsInScrape() {
        metrics.tokenRevocationHit(com.sapari.live.application.port.TokenRevocationAxis.JTI);
        metrics.tokenRevocationHit(com.sapari.live.application.port.TokenRevocationAxis.SID);
        metrics.tokenRevocationLookupFailed();

        String scrape = registry.scrape();

        assertThat(scrape)
                .as("적중과 실패는 이름이 달라야 한다 — 태그 키 집합이 다르기 때문이다")
                .contains("live_token_revoked_total")
                .contains("live_token_revocation_failed_total");
        assertThat(scrape)
                .as("축 태그 값은 소문자다 — 문서가 대문자로 적으면 쿼리가 시계열을 못 찾는다")
                .contains("axis=\"jti\"")
                .contains("axis=\"sid\"");
        assertThat(scrape)
                .as("대문자 값은 나오지 않는다 — 문서 쪽 드리프트를 잡는 면이다")
                .doesNotContain("axis=\"JTI\"")
                .doesNotContain("axis=\"SID\"");
        assertThat(scrape)
                .as("실패 쪽에는 축이 없다 — 묻지 못한 것에 어느 축이 적중했는지가 없다")
                .doesNotContain("live_token_revocation_failed_total{axis");
    }

    @Test
    @DisplayName("⭐ 룸 토큰 발급 출처 지표의 이름·태그 값이 스크레이프 출력에 있다")
    void roomTokenSignature_appearsInScrape() {
        metrics.roomTokenIssued(RoomTokenSignatureSource.SIGNED);
        metrics.roomTokenIssued(RoomTokenSignatureSource.CACHED);

        String scrape = registry.scrape();

        assertThat(scrape)
                .as("두 출처는 같은 이름의 태그다 — 합이 회원 발급 전수라 비율을 구할 수 있어야 한다")
                .contains("live_token_signature_total");
        assertThat(scrape)
                .as("태그 값은 소문자다 — 문서가 대문자로 적으면 쿼리가 시계열을 못 찾는다")
                .contains("source=\"signed\"")
                .contains("source=\"cached\"");
        assertThat(scrape)
                .as("대문자 값은 나오지 않는다 — 문서 쪽 드리프트를 잡는 면이다")
                .doesNotContain("source=\"SIGNED\"")
                .doesNotContain("source=\"CACHED\"");
    }
}
